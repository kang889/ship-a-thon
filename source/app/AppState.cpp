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
int AppState::BringLead(const std::string &eventId) const {
    const auto timing = mTiming.find(eventId);
    return !mAdaptiveTimingEnabled || timing == mTiming.end()
               ? mWeights.bringLead
               : AdaptiveTiming::Preferred(timing->second, mWeights.minimumSamples, 15, mWeights.bringLead);
}
AppState::DayOuting AppState::DailyOuting(Minute day, Minute now) const {
    DayOuting outing;
    std::vector<DailyOutingEngine::Contribution> contributions;
    // Occurrences are ordered by start, so the first qualifying values captured are the earliest.
    for (const auto &occurrence : TimetableEngine::Between(mEvents, day, day)) {
        if (outing.title.empty())
            outing.title = occurrence.event.title;
        // The single daily Bring reminder fires when the student leaves for the day's outing: the
        // first occurrence today that has not finished yet. Its lead honours AdaptiveTiming.
        if (occurrence.end > now &&
            (!outing.hasFirstRelevant || occurrence.start < outing.firstRelevantStart)) {
            outing.firstRelevantStart = occurrence.start;
            outing.firstRelevantLead = BringLead(occurrence.event.id);
            outing.hasFirstRelevant = true;
        }
        for (const auto &item : occurrence.event.items) {
            if (item.onlyDay >= 0 && item.onlyDay != day)
                continue;
            const auto state = State(occurrence, item);
            // Classify at the moment the reminder would fire so a future high-risk plan is not
            // missed; the daily item keeps the maximum priority among its underlying requirements.
            const auto bringPriority = ReminderDecisionEngine::Classify(
                Risk(occurrence, item, occurrence.start - outing.firstRelevantLead), mWeights);
            const auto viewPriority = ReminderDecisionEngine::Classify(Risk(occurrence, item, now), mWeights);
            contributions.push_back({occurrence.key, item.id, item.name, occurrence.event.title, state,
                                     std::max(bringPriority, viewPriority)});
        }
    }
    outing.items = DailyOutingEngine::Aggregate(contributions);
    return outing;
}
void AppState::TransitionDay(Minute day, const std::string &name, ItemState target, Minute now) {
    const auto normalized = DailyOutingEngine::Normalize(name);
    bool matched = false;
    // Fan out to every same-day occurrence whose item shares the normalized name. Each underlying
    // requirement advances through its own valid lifecycle via Transition; instances already at or
    // beyond the target (further along) are left untouched so the day-level action never regresses
    // them and stays idempotent on repeat. Other days are never touched (only `day` is queried).
    for (const auto &occurrence : TimetableEngine::Between(mEvents, day, day)) {
        for (const auto &item : occurrence.event.items) {
            if (item.onlyDay >= 0 && item.onlyDay != day)
                continue;
            if (DailyOutingEngine::Normalize(item.name) != normalized)
                continue;
            matched = true;
            if (ItemLifecycleEngine::CanTransition(State(occurrence, item), target))
                Transition(occurrence.key, item.id, target, now);
        }
    }
    if (!matched)
        throw std::invalid_argument("No matching daily item for that day.");
}
void AppState::ReturnItem(const std::string &occurrenceKey, const std::string &itemId, Minute now) {
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
            const auto state = State(occurrence, item);
            // Already returned for this class -> idempotent no-op success (repeat taps are safe).
            if (state == ItemState::Safe)
                return;
            // Class-exit confirmation is only valid once the item is with the student for the day
            // (Packed) or mid-return. This is a deliberate, user-driven shortcut to Safe for THIS
            // occurrence only; it does not run through CanTransition and touches no other copy.
            if (!ItemLifecycleEngine::ShouldBringBackFromClass(state))
                throw std::invalid_argument("There is nothing to bring back for that item yet.");
            mStates[StateKey(occurrenceKey, itemId)] = ItemState::Safe;
            auto &stats = mProfile[ProfileKey(occurrence.event, item)];
            ++stats.returned;
            stats.lastSuccess = now;
            return;
        }
    }
    throw std::invalid_argument("Item or occurrence no longer exists.");
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
    } else if (action == "transition_day") {
        // Day-level packing: mark a physical item (by normalized name) for the whole day's outing.
        // All matching same-day requirements advance together; the propagation lives in C++ so the
        // user never marks the same item packed once per class.
        const auto raw = command.at("target").get<std::string>();
        const auto target = command.at("target").get<ItemState>();
        if (Json(target) != raw)
            throw std::invalid_argument("Unknown item state.");
        TransitionDay(command.at("day").get<Minute>(), command.at("name").get<std::string>(), target, now);
    } else if (action == "return_item") {
        // Class-exit one-tap confirmation: "I have this item with me leaving this class."
        // Completes this single occurrence's item to Safe from Packed/Brought/InUse/NeedsToReturn.
        ReturnItem(command.at("occurrence"), command.at("item"), now);
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
    // Bring is grouped for the whole day (day@<day>:bring), but Bring Back stays PER CLASS: every
    // occurrence gets its own <occurrenceKey>:return reminder at its own end. These per-occurrence
    // return inputs are collected while the event detail cards are built below.
    std::vector<OccurrenceReminders> returnInputs;
    const auto normalTemplate = mTemplates.value("NORMAL", Json::object());
    const auto occurrences = TimetableEngine::Between(mEvents, now / 1440 - 1, now / 1440 + 7);
    for (const auto &occurrence : occurrences) {
        // An ended class stays visible/actionable while it still has items awaiting collection.
        // Class-exit semantics: a PACKED item (brought for the day but never manually advanced)
        // still needs confirming when leaving the class, so it keeps the occurrence in view.
        const bool awaitingReturn =
            std::any_of(occurrence.event.items.begin(), occurrence.event.items.end(), [&](const auto &item) {
                return ItemLifecycleEngine::ShouldBringBackFromClass(State(occurrence, item));
            });
        if (occurrence.end < now && !awaitingReturn)
            continue;
        const auto timing = mTiming.find(occurrence.event.id);
        const int lead =
            !mAdaptiveTimingEnabled || timing == mTiming.end()
                ? mWeights.bringLead
                : AdaptiveTiming::Preferred(timing->second, mWeights.minimumSamples, 15, mWeights.bringLead);
        // Per-event detail card items. The event remains the source of truth for WHY an item is
        // required, so each class card still lists exactly what that class needs. Daily
        // deduplication and packing state are derived separately below; Bring Back stays per class.
        static_cast<void>(lead);
        Json items = Json::array();
        // Per-occurrence Bring Back: items of THIS class currently awaiting return, deduplicated by
        // normalized name (a class rarely repeats a name, but keep it safe) with the maximum return
        // priority among them retained as metadata.
        std::string back;
        std::vector<std::string> backNormalized;
        auto maxReturn = Priority::Low;
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
            // Class-exit one-tap return applies whenever the item is with the student for the day
            // (PACKED) or mid-return. The Bring Back UI uses this to call `return_item` directly,
            // instead of walking PACKED -> BROUGHT -> NEEDS_TO_RETURN -> SAFE by hand.
            const bool canReturn = ItemLifecycleEngine::ShouldBringBackFromClass(state);
            items.push_back({{"id", item.id},
                             {"name", item.name},
                             {"state", state},
                             {"risk", risk},
                             {"priority", PriorityName(priority)},
                             {"reason", reason},
                             {"actions", actions},
                             {"canReturn", canReturn}});
            if (canReturn) {
                const auto normalized = DailyOutingEngine::Normalize(item.name);
                if (std::find(backNormalized.begin(), backNormalized.end(), normalized) ==
                    backNormalized.end()) {
                    backNormalized.push_back(normalized);
                    back += (back.empty() ? "" : ", ") + item.name;
                }
                // Score at the return-reminder fire time so a future high-risk plan is not missed.
                maxReturn = std::max(
                    maxReturn, ReminderDecisionEngine::Classify(
                                   Risk(occurrence, item, occurrence.end - mWeights.returnLead), mWeights));
            }
        }
        // One Bring Back reminder for THIS class at its own end (occurrence.end − returnLead), so the
        // student is prompted to take that class's items when leaving that class/location. The empty
        // list is dropped by the plan engine. Bring is intentionally NOT added here (day-level only).
        returnInputs.push_back({occurrence.key, occurrence.event.title, /*bringItems=*/std::string{}, back,
                                normalTemplate.value("bring", "Bring: "),
                                normalTemplate.value("return", "Before you go, check: "),
                                /*bringFireAt=*/now, occurrence.end - mWeights.returnLead, Priority::Low,
                                maxReturn});
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
    // Today's deduplicated daily packing list: the UNION of every applicable item required by any
    // of today's occurrences, folded by normalized name so each physical item appears exactly once.
    const auto todayOuting = DailyOuting(today, now);
    for (const auto &item : todayOuting.items) {
        // Available actions come from the aggregate daily state; tapping fans out to every matching
        // same-day occurrence (transition_day). A daily row that is already fully returned/packed
        // simply offers whatever transitions remain valid for its representative state.
        Json actions = Json::array();
        for (const auto target :
             {ItemState::Packed, ItemState::Brought, ItemState::InUse, ItemState::NeedsToReturn,
              ItemState::Safe, ItemState::Forgotten, ItemState::NotNeeded})
            if (ItemLifecycleEngine::CanTransition(item.state, target))
                actions.push_back(target);
        Json titles = Json::array();
        for (const auto &title : item.titles)
            titles.push_back(title);
        todayBring.push_back(
            {{"id", item.sources.empty() ? std::string{} : item.sources.front().second},
             {"name", item.name},
             {"normalized", item.normalized},
             {"dayItem", true},
             {"day", today},
             {"count", item.count},
             {"eventTitles", titles},
             // First requiring class labels the row when there is a single class;
             // the UI shows "Needed for N classes" when count > 1.
             {"eventTitle", item.titles.empty() ? std::string{} : item.titles.front()},
             {"eventStart", todayOuting.firstRelevantStart},
             {"occurrence", item.sources.empty() ? std::string{} : item.sources.front().first},
             {"state", item.state},
             {"priority", PriorityName(item.priority)},
             {"actions", actions},
             {"weather", false}});
    }
    // ------------------------------------------------------------------
    // ONE daily Bring reminder per calendar day  +  per-class Bring Back.
    // ------------------------------------------------------------------
    // Packing is grouped for the whole day: duplicated items collapse into ONE day@<day>:bring that
    // fires when the student leaves (first relevant event − lead, with the late fallback) and bundles
    // the deduplicated union of still-needed items. Bring Back is NOT day-level — each class keeps its
    // own <occurrenceKey>:return (built above in `returnInputs`) so the student is reminded to take a
    // class's items with them when that class ends, at occurrence.end − returnLead.
    std::vector<OccurrenceReminders> reminderInputs = returnInputs;
    for (Minute day = now / 1440 - 1; day <= now / 1440 + 7; ++day) {
        const auto outing = DailyOuting(day, now);
        std::string bring;
        auto bringPriority = Priority::Low;
        for (const auto &item : outing.items) {
            if (item.state == ItemState::Needed) {
                bring += (bring.empty() ? "" : ", ") + item.name;
                bringPriority = std::max(bringPriority, item.priority);
            }
        }
        // Bring timing: intended = first relevant event start − lead. If already past but the event
        // has not started, fire promptly (now + 1); once it has started there is no initial Bring.
        // This late fallback never feeds AdaptiveTiming (no transition happens here).
        Minute bringFireAt = now; // in the past → dropped by the engine when there is no relevant event
        if (outing.hasFirstRelevant) {
            const Minute intended = outing.firstRelevantStart - outing.firstRelevantLead;
            bringFireAt = now < intended ? intended : now < outing.firstRelevantStart ? now + 1 : intended;
        }
        // The day-level input carries no Bring Back list (empty backItems → no day-level return plan).
        reminderInputs.push_back({"day@" + std::to_string(day), outing.title, bring,
                                  /*backItems=*/std::string{}, normalTemplate.value("bring", "Bring: "),
                                  normalTemplate.value("return", "Before you go, check: "), bringFireAt,
                                  /*returnFireAt=*/now, bringPriority, Priority::Low});
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
