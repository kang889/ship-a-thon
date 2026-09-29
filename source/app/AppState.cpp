#include "app/AppState.hpp"
#include "services/FakeBackend.hpp"
#include "sim/ImportEngine.hpp"
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cmath>
#include <set>
#include <sstream>
#include <stdexcept>

namespace memory {
using Json = nlohmann::json;
namespace {
std::string StateKey(const std::string &occurrence, const std::string &item) {
    return Json::array({occurrence, item}).dump();
}
std::string ProfileKey(const Event &event, const Item &item) {
    return Json::array({event.id, item.id}).dump();
}
std::string PriorityName(Priority priority) {
    switch (priority) {
    case Priority::VeryHigh:
        return "VERY HIGH";
    case Priority::High:
        return "HIGH";
    case Priority::Medium:
        return "MEDIUM";
    default:
        return "LOW";
    }
}
} // namespace
AppState::AppState(const Json &saved, const Weights &weights, const Json &templates)
    : mWeights(weights), mTemplates(templates) {
    const std::array<double, 5> values{weights.frequency, weights.association, weights.importance,
                                       weights.unusualness, weights.urgency};
    double sum = 0;
    for (const double value : values) {
        if (!std::isfinite(value) || value < 0)
            throw std::invalid_argument("Invalid risk weights.");
        sum += value;
    }
    if (std::abs(sum - 1) > .00001 || weights.medium < 0 || weights.medium > weights.high ||
        weights.high > weights.veryHigh || weights.veryHigh > 1 || weights.bringLead < 1 ||
        weights.returnLead < 1 || weights.rainThreshold < 0 || weights.rainThreshold > 100)
        throw std::invalid_argument("Invalid reminder configuration.");
    if (saved.empty())
        return;
    if (saved.at("version") != 1)
        throw std::invalid_argument("Unsupported saved state version.");
    mEvents = saved.at("events").get<std::vector<Event>>();
    mStates = saved.at("states").get<decltype(mStates)>();
    for (const auto &[key, value] : saved.at("states").items()) {
        if (Json(mStates.at(key)) != value)
            throw std::invalid_argument("Saved item state is invalid.");
    }
    mProfile = saved.at("profile").get<decltype(mProfile)>();

    mRainProbability = saved.value("rainProbability", -1);

    if (saved.contains("hourlyWeather")) {
        mHourlyWeather = saved.at("hourlyWeather").get<std::vector<WeatherHour>>();
    }

    mWeatherAt = saved.value("weatherAt", Minute{0});
    mWeatherMock = saved.value("weatherMock", false);
    mUmbrellaPackedDay = saved.value("umbrellaPackedDay", Minute{-1});
    mMemories = saved.value("memories", Json::object());
    if (saved.contains("timing"))
        mTiming = saved.at("timing").get<decltype(mTiming)>();
    mAdaptiveTimingEnabled = saved.value("adaptiveTimingEnabled", false);
    std::set<std::string> eventIds;
    for (const auto &event : mEvents) {
        if (!eventIds.insert(event.id).second)
            throw std::invalid_argument("Duplicate saved event ID.");
        EventEngine::Validate(event);
    }
    for (const auto &[key, stats] : mProfile) {
        static_cast<void>(key);
        if (stats.forgotten < 0 || stats.returned < 0 || stats.ignored < 0 || stats.successful < 0)
            throw std::invalid_argument("Invalid saved forget history.");
    }
}
Json AppState::Save() const {
    return {{"version", 1},
            {"events", mEvents},
            {"states", mStates},
            {"profile", mProfile},
            {"rainProbability", mRainProbability},
            {"hourlyWeather", mHourlyWeather},
            {"weatherAt", mWeatherAt},
            {"weatherMock", mWeatherMock},
            {"umbrellaPackedDay", mUmbrellaPackedDay},
            {"timing", mTiming},
            {"adaptiveTimingEnabled", mAdaptiveTimingEnabled},
            {"memories", mMemories}};
}
ItemState AppState::State(const Occurrence &occurrence, const Item &item) const {
    const auto found = mStates.find(StateKey(occurrence.key, item.id));
    return found == mStates.end() ? ItemState::Needed : found->second;
}
double AppState::Risk(const Occurrence &occurrence, const Item &item, Minute now) const {
    const auto found = mProfile.find(ProfileKey(occurrence.event, item));
    const auto stats = found == mProfile.end() ? ForgetStats{} : found->second;
    const auto relevantTime =
        ItemLifecycleEngine::ShouldReturn(State(occurrence, item)) ? occurrence.end : occurrence.start;
    const auto urgency =
        1.0 - static_cast<double>(std::max<Minute>(0, relevantTime - now)) / mWeights.bringLead;
    return ForgetRiskEngine::Score(stats, item.importance, item.onlyDay >= 0, urgency, mWeights);
}
void AppState::Transition(const std::string &occurrenceKey, const std::string &itemId, ItemState target,
                          Minute now) {
    const auto delimiter = occurrenceKey.rfind('@');
    if (delimiter == std::string::npos)
        throw std::invalid_argument("Invalid occurrence.");
    const auto day = std::stoll(occurrenceKey.substr(delimiter + 1));
    for (const auto &occurrence : TimetableEngine::Between(mEvents, day, day)) {
        if (occurrence.key != occurrenceKey)
            continue;
        for (const auto &item : occurrence.event.items) {
            if (item.id != itemId || (item.onlyDay >= 0 && item.onlyDay != day))
                continue;
            if (!ItemLifecycleEngine::CanTransition(State(occurrence, item), target))
                throw std::invalid_argument("That item action is not valid in its current state.");
            mStates[StateKey(occurrenceKey, itemId)] = target;
            if (target == ItemState::Packed || target == ItemState::Forgotten) {
                auto &bucket = mTiming[occurrence.event.id][static_cast<std::size_t>(AdaptiveTiming::Bucket(
                    static_cast<int>(std::clamp<Minute>(occurrence.start - now, 0, 1440))))];
                ++bucket.attempts;
                if (target == ItemState::Packed)
                    ++bucket.successes;
            }
            auto &stats = mProfile[ProfileKey(occurrence.event, item)];
            if (target == ItemState::Forgotten) {
                ++stats.forgotten;
                stats.lastForgotten = now;
            }
            if (target == ItemState::Safe) {
                ++stats.returned;
                stats.lastSuccess = now;
            }
            return;
        }
    }
    throw std::invalid_argument("Item or occurrence no longer exists.");
}
Json AppState::Execute(const Json &command) {
    const auto action = command.at("action").get<std::string>();
    const auto now = command.at("now").get<Minute>();
    if (now < 1440)
        throw std::invalid_argument("Invalid current time.");
    if (action == "memory_save" || action == "memory_delete" || action == "memory_search") {
        if (action == "memory_save") {
            const auto text = command.at("text").get<std::string>();
            const auto id = command.at("id").get<std::string>();
            if (text.empty() || text.size() > 8000 || id.empty())
                throw std::invalid_argument("Enter a note of 1–8000 characters.");
            mMemories[id] = {{"id", id},
                             {"text", text},
                             {"course", command.value("course", "")},
                             {"event_id", command.value("event_id", "")},
                             {"memory_type", command.value("memory_type", "note")},
                             {"source", command.value("source", "manual")}};
        } else if (action == "memory_delete")
            mMemories.erase(command.at("id").get<std::string>());
        Json found = Json::array();
        if (action == "memory_search") {
            const auto query = command.at("query").get<std::string>();
            if (query.empty())
                throw std::invalid_argument("Enter a search query.");
            std::vector<std::pair<int, Json>> ranked;
            for (const auto &note : mMemories) {
                std::string text = note.at("text");
                std::transform(text.begin(), text.end(), text.begin(),
                               [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
                std::istringstream words(query);
                std::string word;
                int score = 0;
                while (words >> word) {
                    std::transform(word.begin(), word.end(), word.begin(),
                                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
                    if (text.find(word) != std::string::npos)
                        ++score;
                }
                if (score > 0)
                    ranked.emplace_back(score, note);
            }
            std::stable_sort(ranked.begin(), ranked.end(),
                             [](const auto &a, const auto &b) { return a.first > b.first; });
            for (std::size_t i = 0; i < std::min<std::size_t>(5, ranked.size()); ++i)
                found.push_back(ranked[i].second);
        }
        return {{"ok", true}, {"state", Save()}, {"view", View(now)}, {"memories", found}, {"mock", true}};
    }
    if (action == "preview_timetable" || action == "preview_instruction") {
        const auto kind = action == "preview_timetable" ? "timetable" : "instruction";
        Json data;
        bool mock = false;
        if (command.contains("data")) {
            data = command.at("data");
            mock = command.value("mock", false);
        } else {
            FakeBackend backend;
            const auto extracted = backend.Extract(kind, command.at("input"));
            if (!extracted.ok)
                throw std::invalid_argument(extracted.error);
            data = extracted.data;
            mock = true;
        }
        std::vector<Event> preview;
        const auto id = command.at("import_id").get<std::string>();
        if (action == "preview_timetable")
            preview = ImportEngine::Timetable(data, now / 1440, id);
        else {
            const auto found = std::find_if(mEvents.begin(), mEvents.end(), [&](const auto &event) {
                return event.id == command.at("event_id").get<std::string>();
            });
            if (found == mEvents.end())
                throw std::invalid_argument("Choose an existing class first.");
            if (mock) {
                const auto next = RecurrenceEngine::Generate(*found, now / 1440, now / 1440 + 7);
                if (next.empty())
                    throw std::invalid_argument("This event has no upcoming occurrence.");
                const auto date = std::chrono::year_month_day(
                    std::chrono::sys_days(std::chrono::days(next[0].start / 1440)));
                const auto month = static_cast<unsigned>(date.month()),
                           day = static_cast<unsigned>(date.day());
                data["date"] = std::to_string(static_cast<int>(date.year())) + "-" + (month < 10 ? "0" : "") +
                               std::to_string(month) + "-" + (day < 10 ? "0" : "") + std::to_string(day);
            }
            preview.push_back(ImportEngine::Instruction(data, *found, id));
        }
        return {{"ok", true}, {"state", Save()}, {"view", View(now)}, {"preview", preview}, {"mock", mock}};
    }
    if (action == "adaptive_timing") {
        mAdaptiveTimingEnabled = command.at("enabled").get<bool>();
    } else if (action == "weather") {
        std::vector<WeatherHour> hours;
        const auto &points = command.at("hourly");
        if (!points.is_array() || points.size() > 240)
            throw std::invalid_argument("Invalid hourly forecast size.");
        for (const auto &point : points) {
            const auto time = point.at("time").get<Minute>();
            const auto rainProbability = point.at("rain_probability").get<int>();
            if (time < 0 || rainProbability < 0 || rainProbability > 100)
                throw std::invalid_argument("Invalid hourly weather.");
            hours.push_back({time, rainProbability});
        }
        std::sort(hours.begin(), hours.end(),
                  [](const WeatherHour &a, const WeatherHour &b) { return a.time < b.time; });
        mHourlyWeather = std::move(hours);
        mRainProbability = -1;
        mWeatherMock = command.value("mock", false);
        mWeatherAt = now;
    } else if (action == "weather_clear") {
        mHourlyWeather.clear();
        mRainProbability = -1;
        mWeatherAt = 0;
        mWeatherMock = false;
    } else if (action == "umbrella_packed") {
        mUmbrellaPackedDay = command.at("packed").get<bool>() ? now / 1440 : -1;
    } else if (action == "confirm_import") {
        if (!command.value("confirmed", false))
            throw std::invalid_argument("Review and confirm the import before saving.");
        const auto events = command.at("events").get<std::vector<Event>>();
        if (events.empty() || events.size() > 50)
            throw std::invalid_argument("Invalid import size.");
        for (const auto &event : events)
            EventEngine::Validate(event);
        for (const auto &event : events) {
            const auto found =
                std::find_if(mEvents.begin(), mEvents.end(), [&](const auto &e) { return e.id == event.id; });
            if (found == mEvents.end())
                mEvents.push_back(event);
            else
                *found = event;
        }
    } else if (action == "cloud_hydrate") {
        // Additive, idempotent cross-device hydration from the backend. Validate every incoming
        // event BEFORE touching local state so a malformed payload cannot partially mutate it.
        const auto incoming = command.at("events").get<std::vector<Event>>();
        if (incoming.size() > 1000)
            throw std::invalid_argument("Too many cloud events to hydrate.");
        for (const auto &event : incoming)
            EventEngine::Validate(event);
        // Add only events whose ID is not already present. Existing local events are preserved
        // as-is (no overwrite), which keeps hydration non-destructive and idempotent.
        for (const auto &event : incoming) {
            const bool exists =
                std::any_of(mEvents.begin(), mEvents.end(), [&](const auto &e) { return e.id == event.id; });
            if (!exists)
                mEvents.push_back(event);
        }
    } else if (action == "save_event") {
        const auto event = command.at("event").get<Event>();
        EventEngine::Validate(event);
        auto found =
            std::find_if(mEvents.begin(), mEvents.end(), [&](const auto &e) { return e.id == event.id; });
        if (found == mEvents.end())
            mEvents.push_back(event);
        else
            *found = event;
    } else if (action == "delete_event") {
        const auto id = command.at("id").get<std::string>();
        std::erase_if(mEvents, [&](const auto &e) { return e.id == id; });
        // States are keyed by occurrence and must not be inherited if an ID is reused.
        std::erase_if(mStates, [&](const auto &pair) {
            const auto key = Json::parse(pair.first).at(0).template get<std::string>();
            return key.substr(0, key.rfind('@')) == id;
        });
        std::erase_if(mProfile, [&](const auto &pair) { return Json::parse(pair.first).at(0) == id; });
    } else if (action == "transition") {
        const auto raw = command.at("target").get<std::string>();
        const auto target = command.at("target").get<ItemState>();
        if (Json(target) != raw)
            throw std::invalid_argument("Unknown item state.");
        Transition(command.at("occurrence"), command.at("item"), target, now);
    } else if (action == "complete_task") {
        bool found = false;
        for (auto &event : mEvents) {
            if (event.id != command.at("event").get<std::string>())
                continue;
            for (auto &task : event.tasks)
                if (task.id == command.at("task").get<std::string>()) {
                    task.completed = command.at("completed").get<bool>();
                    found = true;
                }
        }
        if (!found)
            throw std::invalid_argument("Task no longer exists.");
    } else if (action != "view") {
        throw std::invalid_argument("Unknown action.");
    }
    return {{"ok", true}, {"state", Save()}, {"view", View(now)}};
}
Json AppState::View(Minute now) const {
    Json events = Json::array(), tasks = Json::array();
    std::vector<OccurrenceReminders> reminderInputs;
    const auto occurrences = TimetableEngine::Between(mEvents, now / 1440 - 1, now / 1440 + 7);
    for (const auto &occurrence : occurrences) {
        const bool awaitingReturn =
            std::any_of(occurrence.event.items.begin(), occurrence.event.items.end(), [&](const auto &item) {
                return ItemLifecycleEngine::ShouldReturn(State(occurrence, item));
            });
        if (occurrence.end < now && !awaitingReturn)
            continue;
        const auto timing = mTiming.find(occurrence.event.id);
        const int lead =
            !mAdaptiveTimingEnabled || timing == mTiming.end()
                ? mWeights.bringLead
                : AdaptiveTiming::Preferred(timing->second, mWeights.minimumSamples, 15, mWeights.bringLead);
        Json items = Json::array();
        std::string bring, back;
        auto maxBring = Priority::Low, maxReturn = Priority::Low;
        for (const auto &item : occurrence.event.items) {
            if (item.onlyDay >= 0 && item.onlyDay != occurrence.start / 1440)
                continue;
            const auto state = State(occurrence, item);
            const auto risk = Risk(occurrence, item, now);
            const auto priority = ReminderDecisionEngine::Classify(risk, mWeights);
            const auto stats = mProfile.find(ProfileKey(occurrence.event, item));
            const auto forgotten = stats == mProfile.end() ? 0 : stats->second.forgotten;
            std::string reason = "Required for " + occurrence.event.title + ". Forgotten here " +
                                 std::to_string(forgotten) + " time(s).";
            Json actions = Json::array();
            for (const auto target :
                 {ItemState::Packed, ItemState::Brought, ItemState::InUse, ItemState::NeedsToReturn,
                  ItemState::Safe, ItemState::Forgotten, ItemState::NotNeeded}) {
                if (ItemLifecycleEngine::CanTransition(state, target))
                    actions.push_back(target);
            }
            items.push_back({{"id", item.id},
                             {"name", item.name},
                             {"state", state},
                             {"risk", risk},
                             {"priority", PriorityName(priority)},
                             {"reason", reason},
                             {"actions", actions}});
            if (state == ItemState::Needed) {
                bring += (bring.empty() ? "" : ", ") + item.name;
                // Use the score at firing time so a future high-risk plan is not missed.
                maxBring = std::max(maxBring, ReminderDecisionEngine::Classify(
                                                  Risk(occurrence, item, occurrence.start - lead), mWeights));
            }
            if (ItemLifecycleEngine::ShouldReturn(state)) {
                back += (back.empty() ? "" : ", ") + item.name;
                maxReturn = std::max(
                    maxReturn, ReminderDecisionEngine::Classify(
                                   Risk(occurrence, item, occurrence.end - mWeights.returnLead), mWeights));
            }
        }
        bool overlap = false;
        for (const auto &other : occurrences)
            if (other.key != occurrence.key && TimetableEngine::Overlaps(occurrence, other))
                overlap = true;
        const auto phase = now >= occurrence.end - mWeights.returnLead ? "BRING BACK"
                           : now >= occurrence.start - 15              ? "NEXT"
                                                                       : "BRING";
        events.push_back({{"key", occurrence.key},
                          {"id", occurrence.event.id},
                          {"title", occurrence.event.title},
                          {"location", occurrence.event.location},
                          {"start", occurrence.start},
                          {"end", occurrence.end},
                          {"phase", phase},
                          {"overlap", overlap},
                          {"items", items}});
        const auto normal = mTemplates.value("NORMAL", Json::object());
        // AdaptiveTiming decides the INTENDED bring time. If that moment has already passed but
        // the event has not started (late-added item/event, app reopened, refresh past the minute),
        // fall back to firing promptly (now + 1) so the reminder is still delivered. Once the event
        // has started there is no initial Bring reminder. This late fallback never feeds AdaptiveTiming.
        const Minute intendedBring = occurrence.start - lead;
        const Minute bringFireAt = now < intendedBring ? intendedBring
                                   : now < occurrence.start
                                       ? now + 1
                                       : intendedBring; // in the past; dropped by the engine
        // Hand the deterministic per-occurrence results to the plan engine; it owns
        // how these become the actual bring/return notifications.
        reminderInputs.push_back({occurrence.key, occurrence.event.title, bring, back,
                                  normal.value("bring", "Bring: "),
                                  normal.value("return", "Before you go, check: "), bringFireAt,
                                  occurrence.end - mWeights.returnLead, maxBring, maxReturn});
    }
    for (const auto &event : mEvents) {
        for (const auto &task : event.tasks) {
            if (task.completed)
                continue;
            const auto deadline = task.deadline > 0 ? task.deadline : event.start;
            std::optional<Minute> slot;
            // Expand the complete scheduling horizon, including overnight events.
            if (deadline > now && deadline / 1440 - now / 1440 < 366) {
                const auto busy = TimetableEngine::Between(mEvents, now / 1440 - 1, deadline / 1440);
                slot = PrepScheduler::FindSlot(now, deadline, task.duration, busy);
            }
            tasks.push_back({{"event", event.id},
                             {"id", task.id},
                             {"title", task.title},
                             {"duration", task.duration},
                             {"deadline", deadline},
                             {"slot", slot ? Json(*slot) : Json(nullptr)}});
        }
    }
    const auto today = now / 1440;

    // Weather window bounds for today's events. A relevant event is one today that has
    // not finished yet (end > now); the first such event defines the preparation time.
    //   calculatedPreparationTime = first relevant event's start minus its reminder lead
    //                               (AdaptiveTiming preferred lead, else fallback bringLead).
    //   WEATHER_WINDOW_START = max(now, calculatedPreparationTime)  (never in the past).
    //   WEATHER_WINDOW_END   = last relevant event's END + 60 minutes.
    // Umbrella is only ever considered when there is a relevant event today.
    bool hasEventToday = false;
    bool hasRelevantEvent = false;
    Minute lastEventStart = 0;
    Minute lastEventEnd = 0;
    Minute firstRelevantStart = 0;
    int firstRelevantLead = mWeights.bringLead;
    std::string lastEventTitle;

    for (const auto &occurrence : occurrences) {
        if (occurrence.start / 1440 != today)
            continue;
        if (!hasEventToday || occurrence.start > lastEventStart) {
            lastEventStart = occurrence.start;
            lastEventTitle = occurrence.event.title;
        }
        if (!hasEventToday || occurrence.end > lastEventEnd)
            lastEventEnd = occurrence.end;
        // The first not-yet-finished event of the remaining day drives preparation. Occurrences
        // are ordered by start, so the earliest qualifying one is captured first.
        if (occurrence.end > now && (!hasRelevantEvent || occurrence.start < firstRelevantStart)) {
            firstRelevantStart = occurrence.start;
            const auto timing = mTiming.find(occurrence.event.id);
            firstRelevantLead = !mAdaptiveTimingEnabled || timing == mTiming.end()
                                    ? mWeights.bringLead
                                    : AdaptiveTiming::Preferred(timing->second, mWeights.minimumSamples, 15,
                                                                mWeights.bringLead);
            hasRelevantEvent = true;
        }
        hasEventToday = true;
    }

    // Preparation time before the first relevant event; the evaluation window never starts in
    // the past, so a completed earlier event cannot push it backwards.
    const Minute preparationTime = hasRelevantEvent ? firstRelevantStart - firstRelevantLead : now;
    const Minute weatherWindowStart = std::max(now, preparationTime);
    const Minute weatherWindowEnd = hasRelevantEvent ? lastEventEnd + 60 : 0;

    int relevantRainProbability = -1;
    const bool fresh = mWeatherAt > 0 && mWeatherAt <= now && now - mWeatherAt <= 60;
    // Evaluate rain from the (non-past) preparation time through 60 minutes after the last event.
    const bool covered = hasRelevantEvent && weatherWindowEnd > weatherWindowStart && fresh &&
                         ContextEngine::CoversWindow(mHourlyWeather, weatherWindowStart, weatherWindowEnd);
    if (covered)
        relevantRainProbability =
            ContextEngine::MaxRainProbability(mHourlyWeather, weatherWindowStart, weatherWindowEnd);

    const bool umbrella =
        covered && ContextEngine::SuggestUmbrella(relevantRainProbability, hasRelevantEvent, mWeights);

    UmbrellaReminder umbrellaPlan;
    Json todayBring = Json::array();
    if (umbrella) {
        todayBring.push_back({{"id", "weather-umbrella"},
                              {"name", "Umbrella"},
                              {"eventTitle", "Rain around today's events"},
                              {"eventStart", 0},
                              {"occurrence", ""},
                              {"state", mUmbrellaPackedDay == today ? "PACKED" : "NEEDED"},
                              {"weather", true}});
        // Remind at the calculated preparation time when it is still in the future; otherwise fire
        // promptly (now + 1) so weather learned after preparation never schedules an alarm in the past.
        const Minute umbrellaFireAt = std::max(preparationTime, now + 1);
        umbrellaPlan = {mUmbrellaPackedDay != today, today, umbrellaFireAt,
                        "Bring: Umbrella · rain likely around today's events"};
    }
    for (const auto &occurrence : TimetableEngine::Between(mEvents, today, today)) {
        for (const auto &item : occurrence.event.items) {
            if (item.onlyDay >= 0 && item.onlyDay != today)
                continue;
            const auto state = State(occurrence, item);
            Json actions = Json::array();
            for (const auto target :
                 {ItemState::Packed, ItemState::Brought, ItemState::InUse, ItemState::NeedsToReturn,
                  ItemState::Safe, ItemState::Forgotten, ItemState::NotNeeded})
                if (ItemLifecycleEngine::CanTransition(state, target))
                    actions.push_back(target);
            todayBring.push_back({{"id", item.id},
                                  {"name", item.name},
                                  {"eventTitle", occurrence.event.title},
                                  {"eventStart", occurrence.start},
                                  {"occurrence", occurrence.key},
                                  {"state", state},
                                  {"priority", PriorityName(ReminderDecisionEngine::Classify(
                                                   Risk(occurrence, item, now), mWeights))},
                                  {"actions", actions},
                                  {"weather", false}});
        }
    }
    const auto reminders = NotificationPlanEngine::Build(reminderInputs, umbrellaPlan, now);
    Json plans = Json::array();
    for (const auto &reminder : reminders)
        plans.push_back({{"id", reminder.id},
                         {"title", reminder.title},
                         {"body", reminder.body},
                         {"fireAt", reminder.fireAt},
                         {"priority", PriorityName(reminder.priority)}});

    return {{"events", events},
            {"todayBring", todayBring},
            {"tasks", tasks},
            {"notifications", plans},

            {"weatherChecked", fresh},
            {"weatherHasEventToday", hasEventToday},
            {"weatherLastEventTitle", lastEventTitle},
            {"weatherLastEventStart", hasEventToday ? lastEventStart : Minute{0}},
            {"weatherWindowStart", hasRelevantEvent ? weatherWindowStart : Minute{0}},
            {"weatherWindowEnd", weatherWindowEnd},
            {"weatherCovered", covered},
            {"weatherRainProbability", relevantRainProbability},

            {"umbrella", umbrella},
            {"weatherMock", mWeatherMock}};
}
} // namespace memory
