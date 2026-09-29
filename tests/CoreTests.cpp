#include "app/AppState.hpp"
#include "services/FakeBackend.hpp"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
using namespace memory;
namespace {
int checks = 0;
void Check(bool ok, const char *message) {
    ++checks;
    if (!ok)
        throw std::runtime_error(message);
}
template <class F> void Rejects(F action, const char *message) {
    bool rejected = false;
    try {
        action();
    } catch (const std::exception &) {
        rejected = true;
    }
    Check(rejected, message);
}
} // namespace
int main(int argc, char **argv) {
    try {
        const Minute day = 20000, start = day * 1440 + 840;
        Event event;
        event.id = "lab";
        event.title = "Programming Lab";
        event.start = start;
        event.end = start + 120;
        event.repeatDays = 7;
        event.items = {{"charger", "Charger", 1.0}, {"laptop", "Laptop", .9}};
        Check(RecurrenceEngine::Generate(event, day, day + 14).size() == 3, "weekly recurrence");
        event.untilDay = day + 7;
        Check(RecurrenceEngine::Generate(event, day, day + 14).size() == 2, "recurrence end inclusive");
        event.untilDay = -1;
        Check(RecurrenceEngine::Generate(event, day + 1, day + 6).empty(), "no spurious recurrence");
        auto second = event;
        second.id = "early";
        second.start -= 30;
        second.end -= 30;
        const auto ordered = TimetableEngine::Between({event, second}, day, day);
        Check(ordered.front().event.id == "early", "timetable ordered");
        Check(TimetableEngine::Overlaps(ordered[0], ordered[1]), "overlap detected");
        auto adjacent = ordered[0];
        adjacent.end = ordered[1].start;
        Check(!TimetableEngine::Overlaps(adjacent, ordered[1]), "adjacent not overlap");
        const std::vector<ItemState> states{ItemState::Needed, ItemState::Packed,        ItemState::Brought,
                                            ItemState::InUse,  ItemState::NeedsToReturn, ItemState::Safe};
        for (std::size_t i = 1; i < states.size(); ++i)
            Check(ItemLifecycleEngine::CanTransition(states[i - 1], states[i]), "valid lifecycle");
        Check(!ItemLifecycleEngine::CanTransition(ItemState::Needed, ItemState::Safe), "invalid lifecycle");
        Check(!ItemLifecycleEngine::CanTransition(ItemState::Forgotten, ItemState::Packed),
              "terminal forgotten");
        Check(!ItemLifecycleEngine::ShouldReturn(ItemState::Needed), "unbrought excluded");
        Check(ItemLifecycleEngine::ShouldReturn(ItemState::InUse), "in-use returns");
        Weights weights;
        Check(std::abs(ForgetRiskEngine::Score({}, 1, false, 0, weights) - .45) < .00001, "risk formula");
        ForgetStats forgotten;
        forgotten.forgotten = 2;
        Check(ForgetRiskEngine::Score(forgotten, 1, false, 0, weights) > .7, "history increases risk");
        Check(ReminderDecisionEngine::Classify(.6, weights) == Priority::High, "high threshold");
        Check(ReminderDecisionEngine::Classify(.8, weights) == Priority::VeryHigh, "very high threshold");
        Check(PrepScheduler::FindSlot(start - 60, start + 180, 60, ordered) == start + 120,
              "prep avoids overlap");
        Check(!PrepScheduler::FindSlot(start - 20, start + 120, 60, ordered), "no impossible prep slot");
        Check(AdaptiveTiming::Bucket(120) == 1 && AdaptiveTiming::Bucket(14) == 4, "timing boundaries");
        std::array<TimingBucket, 5> timing{};
        timing[3] = {10, 8};
        timing[1] = {10, 2};
        Check(AdaptiveTiming::Preferred(timing, 5, 15, 60) == 20, "adaptive timing");
        Check(AdaptiveTiming::Preferred(timing, 5, 30, 60) == 90, "minimum lead");
        {
            AppState basic;
            basic.Execute({{"action", "save_event"}, {"now", start - 300}, {"event", event}});
            auto saved = basic.Save();
            saved["timing"][event.id] = timing;
            AppState subscriptionScoped(saved);
            const auto fixedView = subscriptionScoped.View(start - 180);
            const auto fixed = fixedView["events"][0]["start"].get<Minute>();
            Check(fixed == start, "event start unchanged for fixed reminders");
            auto bringFire = [&](const nlohmann::json &view) {
                for (const auto &plan : view.at("notifications"))
                    if (plan.at("id").get<std::string>().find(":bring") != std::string::npos)
                        return plan.at("fireAt").get<Minute>();
                throw std::runtime_error("expected bring reminder");
            };
            Check(bringFire(fixedView) == start - 60, "Free keeps fixed reminder lead with learned history");
            Check(!subscriptionScoped.Save().at("adaptiveTimingEnabled").get<bool>(),
                  "adaptive timing defaults off while response history persists");
            subscriptionScoped.Execute(
                {{"action", "adaptive_timing"}, {"now", start - 180}, {"enabled", true}});
            Check(subscriptionScoped.Save().at("adaptiveTimingEnabled").get<bool>(),
                  "adaptive timing can be enabled");
            Check(bringFire(subscriptionScoped.View(start - 180)) == start - 20,
                  "Pro uses learned reminder lead");
            AppState reopened(subscriptionScoped.Save());
            Check(reopened.Save().at("adaptiveTimingEnabled").get<bool>(),
                  "adaptive setting survives reload");
            reopened.Execute({{"action", "adaptive_timing"}, {"now", start - 180}, {"enabled", false}});
            Check(!reopened.Save().at("adaptiveTimingEnabled").get<bool>(),
                  "adaptive timing can be disabled");
            Check(bringFire(reopened.View(start - 180)) == start - 60,
                  "ending Pro use returns to fixed reminder lead");
        }

        // --- NotificationPlanEngine: focused unit coverage of plan assembly ---
        {
            const Minute planNow = start - 60;
            // A High-priority bring and a High-priority return both fire in the future.
            OccurrenceReminders full{"lab@20000",    "Programming Lab",        "Charger, Laptop", "Charger",
                                     "Bring: ",      "Before you go, check: ", start - 30,        start + 90,
                                     Priority::High, Priority::VeryHigh};
            auto plans = NotificationPlanEngine::Build({full}, {}, planNow);
            Check(plans.size() == 2, "bring and bring-back plans generated");
            Check(plans[0].id == "lab@20000:bring" && plans[0].fireAt == start - 30,
                  "bring plan id and fire time");
            Check(plans[0].body == "Bring: Charger, Laptop" && plans[0].priority == Priority::High,
                  "bring body bundles items and keeps priority");
            Check(plans[1].id == "lab@20000:return" && plans[1].fireAt == start + 90 &&
                      plans[1].body == "Before you go, check: Charger" &&
                      plans[1].priority == Priority::VeryHigh,
                  "bring-back plan preserves fire time, body and priority");
            Check(plans[0].fireAt <= plans[1].fireAt, "plans ordered by fire time");

            // Empty item lists must never create a spurious notification.
            OccurrenceReminders empty = full;
            empty.bringItems = "";
            empty.backItems = "";
            Check(NotificationPlanEngine::Build({empty}, {}, planNow).empty(),
                  "no plan for empty bring/return lists");

            // Every required item is reminded: LOW priority still produces a notification.
            OccurrenceReminders low = full;
            low.backItems = "";
            low.bringPriority = Priority::Low;
            auto lowPlans = NotificationPlanEngine::Build({low}, {}, planNow);
            Check(lowPlans.size() == 1 && lowPlans[0].priority == Priority::Low,
                  "low priority still produces a bring notification");

            // MEDIUM priority still produces a notification (priority is metadata, not a gate).
            OccurrenceReminders medium = full;
            medium.backItems = "";
            medium.bringPriority = Priority::Medium;
            auto mediumPlans = NotificationPlanEngine::Build({medium}, {}, planNow);
            Check(mediumPlans.size() == 1 && mediumPlans[0].priority == Priority::Medium &&
                      mediumPlans[0].body == "Bring: Charger, Laptop",
                  "medium priority still produces a bring notification with all items");

            // HIGH and VERY HIGH continue to work and keep their priority metadata.
            OccurrenceReminders high = full;
            high.backItems = "";
            high.bringPriority = Priority::High;
            Check(NotificationPlanEngine::Build({high}, {}, planNow)[0].priority == Priority::High,
                  "high priority bring notification retains priority");
            OccurrenceReminders veryHigh = full;
            veryHigh.backItems = "";
            veryHigh.bringPriority = Priority::VeryHigh;
            Check(NotificationPlanEngine::Build({veryHigh}, {}, planNow)[0].priority == Priority::VeryHigh,
                  "very high priority bring notification retains priority");

            // Bring Back items are never suppressed by low/medium priority either.
            OccurrenceReminders back = full;
            back.bringItems = "";
            back.returnPriority = Priority::Low;
            auto backPlans = NotificationPlanEngine::Build({back}, {}, planNow);
            Check(backPlans.size() == 1 && backPlans[0].id == "lab@20000:return" &&
                      backPlans[0].priority == Priority::Low,
                  "low priority bring-back item is still reminded");

            // A fire time already in the past is dropped.
            OccurrenceReminders past = full;
            past.backItems = "";
            past.bringFireAt = planNow - 1;
            Check(NotificationPlanEngine::Build({past}, {}, planNow).empty(), "past bring reminder dropped");

            // Independent occurrences each yield their own plan, ordered by fire time.
            OccurrenceReminders earlier = full;
            earlier.backItems = "";
            earlier.key = "lab@19999";
            earlier.bringFireAt = start - 45;
            OccurrenceReminders laterOcc = full;
            laterOcc.backItems = "";
            laterOcc.key = "lab@20001";
            laterOcc.bringFireAt = start - 10;
            auto multi = NotificationPlanEngine::Build({laterOcc, earlier}, {}, planNow);
            Check(multi.size() == 2 && multi[0].id == "lab@19999:bring" && multi[1].id == "lab@20001:bring",
                  "recurring occurrences remain independent and sorted");

            // The plan's priority metadata is the maximum priority among its bundled items;
            // the caller passes the already-computed maximum (here VeryHigh) through unchanged.
            OccurrenceReminders bundled = full;
            bundled.backItems = "";
            bundled.bringItems = "Laptop, Calculator, Charger";
            bundled.bringPriority = Priority::VeryHigh;
            auto bundledPlan = NotificationPlanEngine::Build({bundled}, {}, planNow);
            Check(bundledPlan[0].body == "Bring: Laptop, Calculator, Charger" &&
                      bundledPlan[0].priority == Priority::VeryHigh,
                  "bundled plan keeps all items and the maximum priority as metadata");

            // AdaptiveTiming still chooses the learned lead once minimum samples exist, and falls
            // back to bringLead otherwise. It only affects WHEN, never whether the plan exists.
            std::array<TimingBucket, 5> learned{};
            learned[3] = {10, 8}; // 15-30 min bucket, representative lead 20
            Check(AdaptiveTiming::Preferred(learned, 5, 15, 60) == 20, "learned timing chosen after samples");
            std::array<TimingBucket, 5> sparse{};
            sparse[3] = {2, 2}; // below minimumSamples
            Check(AdaptiveTiming::Preferred(sparse, 5, 15, 60) == 60, "fallback bringLead without samples");
            OccurrenceReminders adaptive = full;
            adaptive.backItems = "";
            adaptive.bringFireAt = start - AdaptiveTiming::Preferred(learned, 5, 15, 60);
            Check(NotificationPlanEngine::Build({adaptive}, {}, planNow)[0].fireAt == start - 20,
                  "adaptive lead determines bring fire time");

            // Umbrella reminder appears only while active and fires promptly.
            UmbrellaReminder umbrellaActive{true, 20000, planNow + 1, "Bring: Umbrella"};
            auto withUmbrella = NotificationPlanEngine::Build({}, umbrellaActive, planNow);
            Check(withUmbrella.size() == 1 && withUmbrella[0].id == "weather@20000:bring" &&
                      withUmbrella[0].priority == Priority::High && withUmbrella[0].fireAt == planNow + 1,
                  "active umbrella schedules a high-priority bring reminder");
            UmbrellaReminder umbrellaPacked{false, 20000, planNow + 1, "Bring: Umbrella"};
            Check(NotificationPlanEngine::Build({}, umbrellaPacked, planNow).empty(),
                  "inactive umbrella schedules no reminder");
        }
        std::vector<WeatherHour> weather{
            {start - 120, 90}, {start - 60, 20}, {start, 30}, {start + 60, 80}, {start + 120, 40}};

        Check(ContextEngine::MaxRainProbability(weather, start - 60, start + 120) == 80,
              "maximum rain during outing");

        Check(ContextEngine::MaxRainProbability(weather, start + 180, start + 240) == -1,
              "no weather in range");
        Check(ContextEngine::SuggestUmbrella(75, true, weights), "rain context");
        Check(!ContextEngine::SuggestUmbrella(60, true, weights), "60 percent does not trigger");
        Check(ContextEngine::SuggestUmbrella(61, true, weights), "61 percent triggers");
        Check(!ContextEngine::SuggestUmbrella(75, false, weights), "travel required");
        // --- Weather window: [preparation start, last event END + 60] ---
        // event: start .. start+120 (first upcoming). later: start+240 .. start+300 (last).
        // No timing history -> fallback bringLead 60. nowWeather = start-60, so preparation start
        // is min(now, firstStart-60) = start-60 = now. Window end = later.end + 60 = start+360.
        const Minute nowWeather = start - 60;
        auto later = event;
        later.id = "last";
        later.title = "Evening class";
        later.start = start + 240;
        later.end = later.start + 60; // ends start+300
        AppState forecastApp;
        forecastApp.Execute({{"action", "save_event"}, {"now", nowWeather}, {"event", event}});
        forecastApp.Execute({{"action", "save_event"}, {"now", nowWeather}, {"event", later}});
        // Provide contiguous hourly coverage across the whole window (now .. last end + 60).
        auto forecast = [&](int chance, Minute rainAt) {
            nlohmann::json hours = nlohmann::json::array();
            for (Minute hour = nowWeather; hour <= later.end + 60; hour += 60)
                hours.push_back({{"time", hour}, {"rain_probability", hour == rainAt ? chance : 0}});
            return forecastApp
                .Execute({{"action", "weather"}, {"now", nowWeather}, {"mock", true}, {"hourly", hours}})
                .at("view");
        };
        // Window bounds are reported: start = preparation time, end = last event END + 60.
        auto bounds = forecast(0, -1);
        Check(bounds["weatherWindowStart"] == nowWeather, "weather window begins at preparation time");
        Check(bounds["weatherWindowEnd"] == later.end + 60,
              "weather window ends 60 minutes after the last event ends");
        auto noUmbrella = forecast(60, start + 120);
        Check(noUmbrella["umbrella"] == false && noUmbrella["todayBring"][0]["name"] == "Charger",
              "exactly the threshold does not add umbrella to bring list");
        auto decision = forecast(61, start + 120);
        Check(decision["umbrella"] == true && decision["weatherLastEventStart"] == later.start,
              "rain inside the window triggers umbrella");
        // Under the one-day-outing model the daily list is deduplicated by normalized name, so the
        // umbrella still leads and the shared Charger/Laptop each appear once (labelled by the first
        // requiring class), instead of one row per occurrence.
        Check(decision["todayBring"][0]["name"] == "Umbrella" &&
                  decision["todayBring"][1]["eventTitle"] == "Programming Lab" &&
                  decision["todayBring"].size() == 3 && decision["todayBring"][1]["name"] == "Charger" &&
                  decision["todayBring"][2]["name"] == "Laptop",
              "umbrella precedes deduplicated event-labelled daily items");
        // Umbrella reminder fires at the preparation time (here now+1 since prep already reached).
        for (const auto &plan : decision["notifications"])
            if (plan.at("id") == "weather@20000:bring")
                Check(plan.at("fireAt") == nowWeather + 1,
                      "umbrella reminder fires at/after preparation time");
        Check(std::any_of(decision["notifications"].begin(), decision["notifications"].end(),
                          [](const auto &plan) { return plan.at("id") == "weather@20000:bring"; }),
              "rain schedules bring umbrella reminder");
        // Rain at the last event's end hour is inside the [prep, end+60] window -> umbrella required.
        Check(forecast(90, later.end)["umbrella"] == true,
              "rain within 60 minutes after the last event triggers umbrella");
        // Rain at end+60 (the window's exclusive upper bound) is outside -> ignored, window still covered.
        Check(forecast(90, later.end + 60)["umbrella"] == false,
              "rain beyond last event end + 60 does not trigger umbrella");
        // Rain during the preparation window (before the first event) -> umbrella required.
        Check(forecast(90, nowWeather)["umbrella"] == true,
              "rain during preparation window triggers umbrella");
        auto packedUmbrella =
            forecastApp.Execute({{"action", "umbrella_packed"}, {"now", nowWeather}, {"packed", true}})
                .at("view");
        Check(packedUmbrella["todayBring"][0]["state"] == "PACKED" &&
                  std::none_of(packedUmbrella["notifications"].begin(), packedUmbrella["notifications"].end(),
                               [](const auto &plan) { return plan.at("id") == "weather@20000:bring"; }),
              "packed umbrella remains visible without further weather reminder");
        Check(AppState(forecastApp.Save()).View(nowWeather)["todayBring"][0]["state"] == "PACKED",
              "umbrella packed state persists for today");
        Check(decision["weatherRainProbability"] == 61 && decision["weatherCovered"] == true,
              "weather context reports rule inputs");
        Check(forecastApp.View(nowWeather + 61)["weatherChecked"] == false, "stale forecast expires");
        Check(AppState().View(nowWeather)["umbrella"] == false, "no event today means no umbrella");
        // Incomplete coverage of the window must stay conservative and not guess.
        AppState partialForecast;
        partialForecast.Execute({{"action", "save_event"}, {"now", nowWeather}, {"event", later}});
        auto incomplete = partialForecast.Execute(
            {{"action", "weather"},
             {"now", nowWeather},
             {"hourly", nlohmann::json::array({{{"time", nowWeather + 60}, {"rain_probability", 95}}})}});
        Check(incomplete["view"]["umbrella"] == false && incomplete["view"]["weatherCovered"] == false,
              "partial forecast must not guess");
        partialForecast.Execute({{"action", "weather_clear"}, {"now", nowWeather}});
        Check(partialForecast.View(nowWeather)["weatherChecked"] == false, "unavailable clears forecast");

        // --- End-to-end: a brand-new event with only low/medium-risk items still notifies ---
        // No forget history exists, so risk is low; the required items must still be reminded.
        Event fresh;
        fresh.id = "fresh";
        fresh.title = "Tutorial";
        fresh.start = start;
        fresh.end = start + 60;
        fresh.repeatDays = 0;
        fresh.items = {{"laptop", "Laptop", .2}, {"calc", "Calculator", .5}};
        AppState newEvent;
        newEvent.Execute({{"action", "save_event"}, {"now", start - 120}, {"event", fresh}});
        const auto freshView = newEvent.View(start - 120);
        // Fallback lead is 60 min -> the bring reminder fires at start - 60. Reminders are now
        // day-level (one outing per calendar day), so the plan id is keyed by day, not by event.
        bool bringFound = false;
        for (const auto &plan : freshView["notifications"]) {
            if (plan.at("id") == "day@" + std::to_string(day) + ":bring") {
                bringFound = true;
                const std::string body = plan.at("body");
                Check(body.find("Laptop") != std::string::npos &&
                          body.find("Calculator") != std::string::npos,
                      "new-event bring notification includes every required item");
                Check(plan.at("fireAt") == fresh.start - 60, "new-event bring fires at fallback lead");
            }
        }
        Check(bringFound, "brand-new low/medium-risk event still produces a bring notification");
        // The two items appear in the checklist regardless of their low priority.
        Check(freshView["todayBring"].size() == 2, "all required items appear in the checklist");

        // Bring Back at low risk is still reminded end-to-end.
        AppState bringBack;
        bringBack.Execute({{"action", "save_event"}, {"now", start - 120}, {"event", fresh}});
        const auto freshKey = "fresh@" + std::to_string(day);
        for (const auto &target : {"PACKED", "BROUGHT"})
            bringBack.Execute({{"action", "transition"},
                               {"now", start},
                               {"occurrence", freshKey},
                               {"item", "laptop"},
                               {"target", target}});
        // Bring Back is per-occurrence, so the return reminder id is occurrence-scoped, not day-level.
        bool returnFound = false;
        const auto bringBackView = bringBack.View(start);
        for (const auto &plan : bringBackView["notifications"])
            if (plan.at("id") == freshKey + ":return") {
                returnFound = true;
                Check(std::string(plan.at("body")).find("Laptop") != std::string::npos,
                      "low-risk brought item is still in the bring-back notification");
            }
        Check(returnFound, "low-risk bring-back item is not suppressed end-to-end");
        auto recurring = event;
        recurring.id = "repeat";
        recurring.items = {{"laptop", "Laptop", .8, -1}, {"shoes", "Shoes", .8, day}};
        AppState repeating;
        repeating.Execute({{"action", "save_event"}, {"now", nowWeather}, {"event", recurring}});
        const auto firstDayItems = repeating.View(nowWeather)["todayBring"];
        Check(firstDayItems.size() == 2 && firstDayItems[1]["name"] == "Shoes",
              "one-time item appears for selected occurrence");
        const auto nextWeekItems = repeating.View(nowWeather + 7 * 1440)["todayBring"];
        Check(nextWeekItems.size() == 1 && nextWeekItems[0]["name"] == "Laptop",
              "recurring event keeps regular item but omits once-only item");
        repeating.Execute({{"action", "memory_save"},
                           {"now", nowWeather},
                           {"id", "bring_shoes"},
                           {"text", "Bring shoes for Programming Lab"},
                           {"event_id", "repeat"},
                           {"memory_type", "bring_item"},
                           {"source", "event_addon"}});
        Check(repeating.Save()["memories"]["bring_shoes"]["event_id"] == "repeat" &&
                  repeating.Save()["memories"]["bring_shoes"]["memory_type"] == "bring_item",
              "add-on memory retains its event and type");
        AppState app;
        app.Execute({{"action", "save_event"}, {"now", start - 60}, {"event", event}});
        const auto key = "lab@" + std::to_string(day);
        auto change = [&](const std::string &target) {
            app.Execute({{"action", "transition"},
                         {"now", start},
                         {"occurrence", key},
                         {"item", "charger"},
                         {"target", target}});
        };
        Rejects([&] { change("SAFE"); }, "reject invalid transition");
        Rejects([&] { change("GARBAGE"); }, "reject unknown state");
        change("PACKED");
        change("BROUGHT");
        change("IN_USE");
        change("NEEDS_TO_RETURN");
        change("FORGOTTEN");
        const auto saved = app.Save();
        AppState restored(nlohmann::json::parse(saved.dump()));
        Check(restored.Save() == saved, "JSON restart roundtrip");
        Check(restored.View(start)["events"][0]["items"][0]["state"] == "FORGOTTEN", "lifecycle persists");
        const auto next = restored.View(start + 7 * 1440 - 60);
        Check(next["events"][0]["items"][0]["state"] == "NEEDED", "new occurrence resets");
        Check(next["events"][0]["items"][0]["risk"].get<double>() > .7, "next occurrence higher risk");
        Check(next["notifications"].size() == 2, "one bundled reminder per future occurrence");
        Check(next["notifications"][0]["body"].get<std::string>().find("Laptop") != std::string::npos,
              "reminders bundled");
        FakeBackend fake;
        Check(fake.Extract("timetable", "image").ok, "fake extraction");
        fake.offline = true;
        Check(!fake.Extract("timetable", "image").ok, "offline explicit failure");
        Check(!restored.View(start).at("events").empty(), "offline core still works");
        AppState imported;
        const auto draft = imported.Execute({{"action", "preview_timetable"},
                                             {"now", start},
                                             {"import_id", "import"},
                                             {"input", "screenshot"}});
        Check(imported.Save()["events"].empty(), "preview does not save extraction");
        Check(draft["mock"].get<bool>(), "fake output labelled");
        Rejects(
            [&] {
                imported.Execute(
                    {{"action", "confirm_import"}, {"now", start}, {"events", draft["preview"]}});
            },
            "confirmation required");
        imported.Execute({{"action", "confirm_import"},
                          {"now", start},
                          {"confirmed", true},
                          {"events", draft["preview"]}});
        Check(imported.Save()["events"].size() == 1, "confirmed extraction saved");
        const auto instruction = imported.Execute({{"action", "preview_instruction"},
                                                   {"now", start},
                                                   {"import_id", "instruction"},
                                                   {"event_id", "import-0"},
                                                   {"input", "Bring a calculator"}});
        Check(instruction["preview"][0]["items"][0]["onlyDay"].get<Minute>() >= day,
              "instruction scoped to occurrence");
        Check(instruction["preview"][0]["tasks"].size() == 1, "instruction creates prep preview");
        imported.Execute({{"action", "memory_save"},
                          {"now", start},
                          {"id", "note"},
                          {"text", "Bring calculator tomorrow"}});
        AppState notesRestored(imported.Save());
        const auto matches =
            notesRestored.Execute({{"action", "memory_search"}, {"now", start}, {"query", "CALCULATOR"}});
        Check(matches["memories"].size() == 1 && matches["mock"].get<bool>(),
              "offline memory search is labelled and survives restart");
        notesRestored.Execute({{"action", "memory_delete"}, {"now", start}, {"id", "note"}});
        Check(notesRestored
                  .Execute({{"action", "memory_search"}, {"now", start}, {"query", "calculator"}})["memories"]
                  .empty(),
              "offline memory deletion");
        auto corrupted = saved;
        corrupted["states"].begin().value() = "INVALID";
        Rejects([&] { AppState invalid(corrupted); }, "corrupt lifecycle is rejected instead of reset");

        // ============================================================================
        // Regression: corrected weather window start (max, not min) + late Bring fallback
        // ============================================================================
        {
            const Minute wDay = 21000;
            // Build one AppState per scenario with a single event and a forecast applied at `now`.
            // A helper returns the resulting view. Fallback lead is 60 (no timing history).
            auto setup = [&](Minute evStart, Minute evEnd) {
                auto app = std::make_shared<AppState>();
                Event e;
                e.id = "w";
                e.title = "Class";
                e.start = evStart;
                e.end = evEnd;
                e.repeatDays = 0;
                app->Execute({{"action", "save_event"}, {"now", evStart - 600}, {"event", e}});
                return app;
            };
            // Apply a forecast covering [from, to] hourly, with `chance` at hour `rainAt` (0 elsewhere).
            auto applyWeather = [&](AppState &app, Minute now, Minute from, Minute to, int chance,
                                    Minute rainAt) {
                nlohmann::json hours = nlohmann::json::array();
                for (Minute h = from; h <= to; h += 60)
                    hours.push_back({{"time", h}, {"rain_probability", h == rainAt ? chance : 0}});
                return app.Execute({{"action", "weather"}, {"now", now}, {"mock", true}, {"hourly", hours}})
                    .at("view");
            };

            // Event 10:00-11:00 -> start = wDay*1440 + 600, end = +660. Fallback lead 60 -> prep 09:00.
            const Minute evStart = wDay * 1440 + 600, evEnd = wDay * 1440 + 660;
            const Minute prep = evStart - 60; // 09:00

            // CASE 1: now 08:00 (before prep) -> window start = prep (09:00), NOT now.
            {
                auto app = setup(evStart, evEnd);
                const Minute now = wDay * 1440 + 480; // 08:00
                auto v = applyWeather(*app, now, now - 120, evEnd + 120, 0, -1);
                Check(v["weatherWindowStart"].get<Minute>() == prep,
                      "weather window starts at preparation time when now is before it");
            }
            // CASE 2: now 09:30 (after prep) -> window start = now, not the earlier prep time.
            {
                auto app = setup(evStart, evEnd);
                const Minute now = wDay * 1440 + 570; // 09:30
                auto v = applyWeather(*app, now, now - 120, evEnd + 120, 0, -1);
                Check(v["weatherWindowStart"].get<Minute>() == now,
                      "weather window starts at now when preparation time already passed");
            }
            // CASE 3: window end = last event END + 60.
            {
                auto app = setup(evStart, evEnd);
                const Minute now = wDay * 1440 + 480;
                auto v = applyWeather(*app, now, now - 120, evEnd + 120, 0, -1);
                Check(v["weatherWindowEnd"].get<Minute>() == evEnd + 60,
                      "weather window ends 60 minutes after the last event ends");
            }
            // CASE 4: rain only BEFORE the preparation window (08:00, before prep 09:00) -> no umbrella.
            {
                auto app = setup(evStart, evEnd);
                const Minute now = wDay * 1440 + 480; // 08:00 (window starts at prep 09:00)
                auto v = applyWeather(*app, now, now, evEnd + 120, 90, wDay * 1440 + 480);
                Check(v["umbrella"] == false, "rain before the preparation window does not add umbrella");
            }
            // CASE 5: rain inside [prep, lastEnd+60] -> umbrella.
            {
                auto app = setup(evStart, evEnd);
                const Minute now = wDay * 1440 + 480;
                auto v = applyWeather(*app, now, now - 120, evEnd + 120, 90, prep);
                Check(v["umbrella"] == true,
                      "rain inside the preparation-to-last-end+60 window adds umbrella");
            }
            // CASE 6: rain during the 60-minute post-last-event period (at evEnd) -> umbrella.
            {
                auto app = setup(evStart, evEnd);
                const Minute now = wDay * 1440 + 480;
                auto v = applyWeather(*app, now, now - 120, evEnd + 120, 90, evEnd);
                Check(v["umbrella"] == true, "rain within 60 minutes after the last event adds umbrella");
            }
            // CASE 7: rain at the exclusive upper boundary (evEnd+60) -> no umbrella (interval semantics).
            {
                auto app = setup(evStart, evEnd);
                const Minute now = wDay * 1440 + 480;
                auto v = applyWeather(*app, now, now - 120, evEnd + 120, 90, evEnd + 60);
                Check(v["umbrella"] == false,
                      "rain at/after the exclusive window boundary does not add umbrella");
            }
            // CASE 8: forecast known before prep -> umbrella reminder fires at preparation time.
            {
                auto app = setup(evStart, evEnd);
                const Minute now = wDay * 1440 + 480; // 08:00, prep 09:00 still future
                auto v = applyWeather(*app, now, now - 120, evEnd + 120, 90, prep);
                bool found = false;
                for (const auto &p : v["notifications"])
                    if (p.at("id") == "weather@" + std::to_string(wDay) + ":bring") {
                        found = true;
                        Check(p.at("fireAt").get<Minute>() == prep,
                              "umbrella reminder fires at preparation time when it is still future");
                    }
                Check(found, "umbrella reminder scheduled when rain known before preparation");
            }
            // CASE 9: forecast actionable AFTER prep -> umbrella reminder fires at now + 1.
            {
                auto app = setup(evStart, evEnd);
                const Minute now = wDay * 1440 + 570; // 09:30, prep 09:00 already passed
                auto v = applyWeather(*app, now, now - 120, evEnd + 120, 90, now);
                bool found = false;
                for (const auto &p : v["notifications"])
                    if (p.at("id") == "weather@" + std::to_string(wDay) + ":bring") {
                        found = true;
                        Check(p.at("fireAt").get<Minute>() == now + 1,
                              "umbrella reminder fires promptly (now+1) when preparation already passed");
                    }
                Check(found, "umbrella reminder scheduled promptly after preparation passed");
            }
            // CASE 10: packed umbrella -> no duplicate umbrella reminder.
            {
                auto app = setup(evStart, evEnd);
                const Minute now = wDay * 1440 + 480;
                applyWeather(*app, now, now - 120, evEnd + 120, 90, prep);
                auto v =
                    app->Execute({{"action", "umbrella_packed"}, {"now", now}, {"packed", true}}).at("view");
                Check(v["todayBring"][0]["state"] == "PACKED" &&
                          std::none_of(v["notifications"].begin(), v["notifications"].end(),
                                       [&](const auto &p) {
                                           return p.at("id") == "weather@" + std::to_string(wDay) + ":bring";
                                       }),
                      "packed umbrella schedules no duplicate reminder");
            }
            // CASE 11: no remaining/relevant event today -> no umbrella (query after the event ends).
            {
                auto app = setup(evStart, evEnd);
                const Minute now = evEnd + 30; // event already finished
                auto v = applyWeather(*app, now, now - 120, now + 240, 90, now + 60);
                Check(v["umbrella"] == false, "no remaining relevant event means no umbrella");
            }
            // CASE 12: incomplete forecast coverage of the window -> conservative, no umbrella.
            {
                auto app = setup(evStart, evEnd);
                const Minute now = wDay * 1440 + 480;
                // Only one hour provided -> window not fully covered.
                auto v = app->Execute({{"action", "weather"},
                                       {"now", now},
                                       {"hourly",
                                        nlohmann::json::array({{{"time", prep}, {"rain_probability", 95}}})}})
                             .at("view");
                Check(v["umbrella"] == false && v["weatherCovered"] == false,
                      "incomplete forecast coverage stays conservative");
            }

            // ---- Late / missed Bring reminder ----
            // Event 18:00-19:00, fallback lead 60 -> intended bring 17:00.
            const Minute bStart = wDay * 1440 + 1080, bEnd = wDay * 1440 + 1140;
            const Minute intended = bStart - 60; // 17:00
            auto bringApp = [&]() {
                auto app = std::make_shared<AppState>();
                Event e;
                e.id = "w";
                e.title = "Class";
                e.start = bStart;
                e.end = bEnd;
                e.repeatDays = 0;
                e.items = {{"laptop", "Laptop", .5}};
                app->Execute({{"action", "save_event"}, {"now", bStart - 600}, {"event", e}});
                return app;
            };
            auto bringPlan = [&](Minute now) -> nlohmann::json {
                auto app = bringApp();
                const auto view = app->View(now);
                for (const auto &p : view["notifications"])
                    if (p.at("id") == "day@" + std::to_string(wDay) + ":bring")
                        return p;
                return nullptr;
            };
            // CASE 13: now < intended -> fire time stays intended.
            {
                auto p = bringPlan(wDay * 1440 + 900); // 15:00 < 17:00
                Check(!p.is_null() && p.at("fireAt").get<Minute>() == intended,
                      "bring reminder fires at intended time when it is still future");
            }
            // CASE 14: now == intended -> schedule promptly at now + 1.
            {
                auto p = bringPlan(intended);
                Check(!p.is_null() && p.at("fireAt").get<Minute>() == intended + 1,
                      "bring reminder fires promptly when now equals the intended time");
            }
            // CASE 15: intended < now < start -> schedule promptly at now + 1.
            {
                const Minute now = wDay * 1440 + 1050; // 17:30, between 17:00 and 18:00
                auto p = bringPlan(now);
                Check(!p.is_null() && p.at("fireAt").get<Minute>() == now + 1,
                      "late-added item before start fires promptly at now+1");
            }
            // CASE 16: now >= start -> no initial Bring reminder.
            {
                auto p = bringPlan(bStart); // exactly at start
                Check(p.is_null(), "no initial bring reminder once the event has started");
            }
            // CASE 17: the late fallback still includes ALL needed items regardless of priority.
            {
                auto app = std::make_shared<AppState>();
                Event e;
                e.id = "w";
                e.title = "Class";
                e.start = bStart;
                e.end = bEnd;
                e.repeatDays = 0;
                e.items = {{"laptop", "Laptop", .1}, {"calc", "Calculator", .5}};
                app->Execute({{"action", "save_event"}, {"now", bStart - 600}, {"event", e}});
                const Minute now = wDay * 1440 + 1050; // 17:30 late
                nlohmann::json p = nullptr;
                const auto lateView = app->View(now);
                for (const auto &plan : lateView["notifications"])
                    if (plan.at("id") == "day@" + std::to_string(wDay) + ":bring")
                        p = plan;
                Check(!p.is_null(), "late low/medium-risk bring still notifies");
                const std::string body = p.at("body");
                Check(body.find("Laptop") != std::string::npos &&
                          body.find("Calculator") != std::string::npos,
                      "late bring fallback still bundles every needed item");
                Check(p.at("fireAt").get<Minute>() == now + 1, "late bring fallback fires at now+1");
            }
            // CASE 18: the late fallback does NOT mutate AdaptiveTiming history.
            {
                auto app = bringApp();
                app->View(wDay * 1440 + 1050); // trigger a late fallback bring
                // No transitions occurred, so timing history must be absent from saved state.
                const auto saved2 = app->Save();
                Check(!saved2.contains("timing") || saved2.at("timing").empty(),
                      "late bring fallback does not create AdaptiveTiming history");
            }
            // CASE 19: normal learned AdaptiveTiming still chooses the learned lead.
            {
                std::array<TimingBucket, 5> learned{};
                learned[3] = {10, 8}; // 15-30 min bucket -> lead 20
                Check(AdaptiveTiming::Preferred(learned, 5, 15, 60) == 20,
                      "learned adaptive timing still returns the learned lead");
            }
            // CASE 20: normal fallback bringLead still applies without sufficient history.
            {
                std::array<TimingBucket, 5> sparse{};
                sparse[3] = {2, 2};
                Check(AdaptiveTiming::Preferred(sparse, 5, 15, 60) == 60,
                      "fallback bringLead still applies without sufficient history");
            }
        }

        // ============================================================================
        // Cross-device cloud hydration: additive, idempotent, dedupe-by-id, non-destructive
        // ============================================================================
        {
            const Minute hDay = 22000, hStart = hDay * 1440 + 600, hNow = hStart - 120;
            auto cloudEvent = [&](const std::string &id, const std::string &title) {
                return nlohmann::json{
                    {"id", id}, {"title", title}, {"start", hStart}, {"end", hStart + 60}, {"repeatDays", 0}};
            };
            auto hydrate = [&](AppState &app, const nlohmann::json &events) {
                // Force an explicit JSON array to avoid nlohmann brace-init ambiguity for a
                // single-element list (which would otherwise be read as the object itself).
                nlohmann::json list = events.is_array() ? events : nlohmann::json::array({events});
                return app.Execute({{"action", "cloud_hydrate"}, {"now", hNow}, {"events", list}});
            };
            auto ids = [](const nlohmann::json &state) {
                std::vector<std::string> out;
                for (const auto &e : state.at("events"))
                    out.push_back(e.at("id"));
                std::sort(out.begin(), out.end());
                return out;
            };

            // 1. Hydrate a missing cloud event -> event added.
            {
                AppState app;
                auto st = hydrate(app, {cloudEvent("cloud-a", "Lecture A")}).at("state");
                Check(st.at("events").size() == 1 && st.at("events")[0].at("id") == "cloud-a",
                      "cloud hydrate adds a missing event");
            }
            // 2 & 3. Hydrating the same event twice / many times -> no duplicate, state identical.
            {
                AppState app;
                hydrate(app, {cloudEvent("cloud-a", "Lecture A")});
                const auto afterFirst = app.Save();
                for (int i = 0; i < 10; ++i)
                    hydrate(app, {cloudEvent("cloud-a", "Lecture A")});
                const auto afterMany = app.Save();
                Check(afterMany.at("events").size() == 1, "repeated hydration keeps a single event");
                Check(afterFirst.at("events") == afterMany.at("events"),
                      "hydrating the same event many times leaves state identical");
            }
            // 4. A local event with the same ID exists -> local event preserved (not overwritten).
            {
                AppState app;
                Event local;
                local.id = "cloud-a";
                local.title = "Local title";
                local.start = hStart;
                local.end = hStart + 60;
                local.repeatDays = 0;
                app.Execute({{"action", "save_event"}, {"now", hNow}, {"event", local}});
                auto st = hydrate(app, {cloudEvent("cloud-a", "Cloud title")}).at("state");
                Check(st.at("events").size() == 1 && st.at("events")[0].at("title") == "Local title",
                      "existing local event is preserved, not overwritten by cloud data");
            }
            // 5. Multiple distinct cloud events -> all added once.
            {
                AppState app;
                auto st = hydrate(app, {cloudEvent("cloud-a", "A"), cloudEvent("cloud-b", "B"),
                                        cloudEvent("cloud-c", "C")})
                              .at("state");
                Check(ids(st) == std::vector<std::string>{"cloud-a", "cloud-b", "cloud-c"},
                      "multiple distinct cloud events are all added once");
                // Re-hydrate a mix of existing + new; only the new one is added.
                auto st2 = hydrate(app, {cloudEvent("cloud-b", "B"), cloudEvent("cloud-d", "D")}).at("state");
                Check(ids(st2) == std::vector<std::string>{"cloud-a", "cloud-b", "cloud-c", "cloud-d"},
                      "re-hydration adds only genuinely new events");
            }
            // 6. Malformed hydration input -> rejected with no partial mutation.
            {
                AppState app;
                hydrate(app, {cloudEvent("cloud-a", "A")}); // one valid event present
                const auto before = app.Save();
                // Second event is invalid (end <= start); the whole batch must be rejected.
                nlohmann::json bad = nlohmann::json::array();
                bad.push_back(cloudEvent("cloud-b", "B"));
                bad.push_back({{"id", "cloud-c"},
                               {"title", "Bad"},
                               {"start", hStart},
                               {"end", hStart},
                               {"repeatDays", 0}});
                Rejects([&] { hydrate(app, bad); }, "malformed hydration batch is rejected");
                Check(app.Save().at("events") == before.at("events"),
                      "a malformed hydration batch does not partially mutate local state");
            }
        }

        // ============================================================================
        // One-school-day-as-one-outing: deduplicated daily packing list, single daily
        // Bring + end-of-day Bring Back reminders, and day-level packed-state propagation.
        // The daily outing is DERIVED from ordinary events (same records cloud sync carries),
        // so nothing here changes the event/item schema or the cloud contract.
        // ============================================================================
        {
            const Minute oDay = 30000, oStart = oDay * 1440 + 540; // 09:00
            // Build three same-day classes. Names use varied spelling/whitespace/casing on purpose.
            auto classEvent = [&](const std::string &id, const std::string &title, Minute at,
                                  std::vector<Item> items) {
                Event e;
                e.id = id;
                e.title = title;
                e.start = at;
                e.end = at + 60;
                e.repeatDays = 0;
                e.items = std::move(items);
                return e;
            };
            // Helper: find a daily item row by display name in todayBring.
            auto dailyRow = [](const nlohmann::json &view, const std::string &name) -> nlohmann::json {
                for (const auto &row : view.at("todayBring"))
                    if (row.at("name") == name)
                        return row;
                return nullptr;
            };
            auto dayBringBody = [&](const nlohmann::json &view, Minute day) -> std::string {
                for (const auto &plan : view.at("notifications"))
                    if (plan.at("id") == "day@" + std::to_string(day) + ":bring")
                        return plan.at("body").get<std::string>();
                return {};
            };

            // TEST 1: three same-day events all require Laptop -> Laptop appears exactly once.
            {
                AppState app;
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("calc", "Calculus", oStart, {{"l1", "Laptop", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("dsa", "DSA", oStart + 180, {{"l2", "Laptop", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("os", "OS", oStart + 360, {{"l3", "Laptop", .5}})}});
                const auto view = app.View(oStart - 300);
                int laptopRows = 0;
                for (const auto &row : view["todayBring"])
                    if (row["name"] == "Laptop")
                        ++laptopRows;
                Check(laptopRows == 1, "three same-day Laptop requirements collapse to one daily item");
                Check(dailyRow(view, "Laptop")["count"] == 3, "daily Laptop reports all three classes");
            }

            // TEST 2: "Laptop" + "laptop" + " Laptop " are one daily item (normalized name).
            {
                AppState app;
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("a", "A", oStart, {{"i1", "Laptop", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("b", "B", oStart + 120, {{"i2", "laptop", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("c", "C", oStart + 240, {{"i3", " Laptop ", .5}})}});
                const auto view = app.View(oStart - 300);
                int rows = 0;
                for (const auto &row : view["todayBring"])
                    ++rows;
                Check(rows == 1, "case/space variants of Laptop collapse to one daily item");
                // The preserved display name is the FIRST encountered original spelling.
                Check(view["todayBring"][0]["name"] == "Laptop", "first-seen spelling is preserved");
                Check(view["todayBring"][0]["count"] == 3, "all three variants counted as one item");
            }

            // TEST 3: different items across classes -> union contains all unique items.
            {
                AppState app;
                app.Execute(
                    {{"action", "save_event"},
                     {"now", oStart - 300},
                     {"event",
                      classEvent("calc", "Calculus", oStart,
                                 {{"l", "Laptop", .5}, {"p", "iPad", .5}, {"c", "Calculator", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("dsa", "DSA", oStart + 180,
                                                  {{"l2", "Laptop", .5}, {"p2", "iPad", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("os", "OS", oStart + 360,
                                                  {{"l3", "Laptop", .5}, {"n", "Notebook", .5}})}});
                const auto view = app.View(oStart - 300);
                Check(view["todayBring"].size() == 4, "union has exactly the four distinct items");
                Check(dailyRow(view, "Laptop")["count"] == 3 && dailyRow(view, "iPad")["count"] == 2 &&
                          dailyRow(view, "Calculator")["count"] == 1 &&
                          dailyRow(view, "Notebook")["count"] == 1,
                      "each daily item reports how many classes need it");
            }

            // TEST 4: the same item on different calendar days is NOT combined.
            {
                AppState app;
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("today", "Today", oStart, {{"l", "Laptop", .5}})}});
                app.Execute(
                    {{"action", "save_event"},
                     {"now", oStart - 300},
                     {"event", classEvent("tmrw", "Tomorrow", oStart + 1440, {{"l2", "Laptop", .5}})}});
                const auto todayView = app.View(oStart - 300);
                Check(dailyRow(todayView, "Laptop")["count"] == 1,
                      "today's outing counts only today's Laptop requirement");
                const auto tomorrowView = app.View(oStart - 300 + 1440);
                Check(dailyRow(tomorrowView, "Laptop")["count"] == 1,
                      "tomorrow's outing counts only tomorrow's Laptop requirement");
            }

            // TEST 5: an onlyDay item is included only on its applicable day.
            {
                AppState app;
                Event e =
                    classEvent("rep", "Lab", oStart, {{"l", "Laptop", .5, -1}, {"s", "Shoes", .5, oDay}});
                e.repeatDays = 1; // daily so tomorrow also has an occurrence
                app.Execute({{"action", "save_event"}, {"now", oStart - 300}, {"event", e}});
                const auto todayView = app.View(oStart - 300);
                Check(dailyRow(todayView, "Shoes") != nullptr, "onlyDay Shoes appears on its day");
                const auto tomorrowView = app.View(oStart - 300 + 1440);
                Check(dailyRow(tomorrowView, "Shoes") == nullptr, "onlyDay Shoes is absent on other days");
                Check(dailyRow(tomorrowView, "Laptop") != nullptr,
                      "the recurring item still appears on other days");
            }

            // TEST 6: repeating classes aggregate correctly for today's occurrences.
            {
                AppState app;
                Event weekly = classEvent("wk", "Weekly", oStart, {{"l", "Laptop", .5}});
                weekly.repeatDays = 7;
                Event weekly2 = classEvent("wk2", "Seminar", oStart + 180, {{"l2", "Laptop", .5}});
                weekly2.repeatDays = 7;
                app.Execute({{"action", "save_event"}, {"now", oStart - 300}, {"event", weekly}});
                app.Execute({{"action", "save_event"}, {"now", oStart - 300}, {"event", weekly2}});
                const auto view = app.View(oStart - 300);
                Check(dailyRow(view, "Laptop")["count"] == 2,
                      "two repeating same-day classes aggregate today's Laptop into one item (count 2)");
                // Next week the same aggregation holds and days stay separate.
                const auto nextWeek = app.View(oStart - 300 + 7 * 1440);
                Check(dailyRow(nextWeek, "Laptop")["count"] == 2,
                      "next week's occurrences aggregate the same way");
            }

            // TEST 7: one daily Bring reminder; a repeated Laptop appears once in the body.
            {
                AppState app;
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("calc", "Calculus", oStart, {{"l", "Laptop", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("dsa", "DSA", oStart + 180, {{"l2", "Laptop", .5}})}});
                const auto view = app.View(oStart - 300);
                int bringPlans = 0;
                for (const auto &plan : view["notifications"])
                    if (plan.at("id") == "day@" + std::to_string(oDay) + ":bring")
                        ++bringPlans;
                Check(bringPlans == 1, "exactly one daily Bring reminder for the day");
                const auto body = dayBringBody(view, oDay);
                Check(body.find("Laptop") != std::string::npos, "daily Bring lists Laptop");
                Check(body.find("Laptop") == body.rfind("Laptop"), "Laptop appears exactly once in body");
            }

            // TEST 8: Bring reminder timing is based on the FIRST relevant event of the day,
            // with the existing adaptive/late-fallback behaviour preserved.
            {
                AppState app;
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("first", "First", oStart, {{"l", "Laptop", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("late", "Late", oStart + 300, {{"c", "Calculator", .5}})}});
                // Fallback lead 60 -> bring fires at first event start - 60, not the later event's.
                auto bringFire = [&](Minute now) -> Minute {
                    const auto probe = app.View(now);
                    for (const auto &plan : probe["notifications"])
                        if (plan.at("id") == "day@" + std::to_string(oDay) + ":bring")
                            return plan.at("fireAt").get<Minute>();
                    return -1;
                };
                Check(bringFire(oStart - 300) == oStart - 60,
                      "daily Bring fires at the first relevant event start minus the lead");
                // Late fallback: intended time already passed but first event not started -> now + 1.
                const Minute late = oStart - 30;
                Check(bringFire(late) == late + 1,
                      "late daily Bring falls back to firing promptly (now + 1)");
                // Once the first event has started there is no initial daily Bring.
                bool hasBring = false;
                const auto startedView = app.View(oStart);
                for (const auto &plan : startedView["notifications"])
                    if (plan.at("id") == "day@" + std::to_string(oDay) + ":bring")
                        hasBring = true;
                Check(!hasBring, "no daily Bring once the first relevant event has started");
            }

            // TEST 9 & 10: Bring Back is PER CLASS. Every occurrence gets its own return reminder at
            // its own end (occurrence.end − returnLead); there is NO end-of-day day@<day>:return.
            // Names are deduplicated within a class and priority metadata is preserved.
            {
                const int rl = Weights{}.returnLead;
                AppState app;
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("calc", "Calculus", oStart,
                                                  {{"l", "Laptop", .5}, {"c", "Calculator", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("dsa", "DSA", oStart + 180,
                                                  {{"l2", "Laptop", .5}, {"p", "iPad", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("os", "OS", oStart + 360,
                                                  {{"l3", "Laptop", .5}, {"n", "Notebook", .5}})}});
                const Minute calcEnd = oStart + 60, dsaEnd = oStart + 240, osEnd = oStart + 420;
                // Bring every item so each is awaiting return in its own class.
                auto carry = [&](const std::string &occ, const std::string &id) {
                    for (const auto *t : {"PACKED", "BROUGHT"})
                        app.Execute({{"action", "transition"},
                                     {"now", oStart},
                                     {"occurrence", occ},
                                     {"item", id},
                                     {"target", t}});
                };
                const auto calcKey = "calc@" + std::to_string(oDay);
                const auto dsaKey = "dsa@" + std::to_string(oDay);
                const auto osKey = "os@" + std::to_string(oDay);
                carry(calcKey, "l");
                carry(calcKey, "c");
                carry(dsaKey, "l2");
                carry(dsaKey, "p");
                carry(osKey, "l3");
                carry(osKey, "n");
                const auto view = app.View(oStart + 30);
                // No day-level return reminder must exist.
                Check(std::none_of(view["notifications"].begin(), view["notifications"].end(),
                                   [&](const auto &p) {
                                       return p.at("id") == "day@" + std::to_string(oDay) + ":return";
                                   }),
                      "there is no end-of-day day@<day>:return reminder");
                // Each class has exactly one occurrence-scoped return reminder at its own end.
                auto returnPlan = [&](const std::string &key) -> nlohmann::json {
                    for (const auto &p : view["notifications"])
                        if (p.at("id") == key + ":return")
                            return p;
                    return nullptr;
                };
                const auto calcReturn = returnPlan(calcKey);
                const auto dsaReturn = returnPlan(dsaKey);
                const auto osReturn = returnPlan(osKey);
                Check(!calcReturn.is_null() && calcReturn.at("fireAt").get<Minute>() == calcEnd - rl,
                      "Calculus gets its own return reminder at Calculus end");
                Check(!dsaReturn.is_null() && dsaReturn.at("fireAt").get<Minute>() == dsaEnd - rl,
                      "DSA gets its own return reminder at DSA end");
                Check(!osReturn.is_null() && osReturn.at("fireAt").get<Minute>() == osEnd - rl,
                      "OS gets its own return reminder at OS end");
                // Each class's Bring Back lists exactly its own items (Laptop once per class).
                const std::string calcBody = calcReturn.at("body");
                Check(calcBody.find("Laptop") != std::string::npos &&
                          calcBody.find("Calculator") != std::string::npos &&
                          calcBody.find("iPad") == std::string::npos,
                      "Calculus Bring Back lists only Calculus items");
                const std::string dsaBody = dsaReturn.at("body");
                Check(dsaBody.find("Laptop") != std::string::npos &&
                          dsaBody.find("iPad") != std::string::npos &&
                          dsaBody.find("Notebook") == std::string::npos,
                      "DSA Bring Back lists only DSA items");
                const std::string osBody = osReturn.at("body");
                Check(osBody.find("Laptop") != std::string::npos &&
                          osBody.find("Notebook") != std::string::npos &&
                          osBody.find("Calculator") == std::string::npos,
                      "OS Bring Back lists only OS items");
                // TEST 6: an item used only in the first class still gets a Bring Back after it.
                Check(calcBody.find("Calculator") != std::string::npos,
                      "first-class-only item (Calculator) still returned after that class");
                // TEST 7: an item used in later classes too still gets a Bring Back after each class.
                Check(calcBody.find("Laptop") != std::string::npos &&
                          dsaBody.find("Laptop") != std::string::npos &&
                          osBody.find("Laptop") != std::string::npos,
                      "Laptop is returned after every class it appears in");
            }

            // TEST 5: after the first class's Bring Back, a repeated item is NOT treated as newly
            // Needed for the next class — it stays carried and is not asked to be packed again.
            {
                AppState app;
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("calc", "Calculus", oStart, {{"l", "Laptop", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("dsa", "DSA", oStart + 180, {{"l2", "Laptop", .5}})}});
                const auto calcKey = "calc@" + std::to_string(oDay);
                const auto dsaKey = "dsa@" + std::to_string(oDay);
                // Pack the day's Laptop once (propagates to both classes), then carry + return it in
                // Calculus only.
                app.Execute({{"action", "transition_day"},
                             {"now", oStart - 60},
                             {"day", oDay},
                             {"name", "Laptop"},
                             {"target", "PACKED"}});
                for (const auto *t : {"BROUGHT", "IN_USE", "NEEDS_TO_RETURN", "SAFE"})
                    app.Execute({{"action", "transition"},
                                 {"now", oStart + 30},
                                 {"occurrence", calcKey},
                                 {"item", "l"},
                                 {"target", t}});
                const auto saved = app.Save();
                // Calculus Laptop is returned (Safe); DSA Laptop is still carried (Packed), NOT Needed.
                Check(saved["states"][nlohmann::json::array({calcKey, "l"}).dump()] == "SAFE",
                      "Calculus Laptop is returned after its class");
                Check(saved["states"][nlohmann::json::array({dsaKey, "l2"}).dump()] == "PACKED",
                      "the next class's Laptop is not reset to Needed by the first class's Bring Back");
                // The daily list must not re-prompt to pack Laptop (it is not Needed).
                const auto view = app.View(oStart + 30);
                Check(dailyRow(view, "Laptop")["state"] != "NEEDED",
                      "daily Laptop is not shown as newly Needed after the first class returns it");
            }

            // ----------------------------------------------------------------------------------
            // Class-exit from PACKED: a day-packed item appears in each class's Bring Back WITHOUT
            // any manual PACKED -> BROUGHT step, and a one-tap return_item confirms it per class.
            // This follows the organic UI flow: mark daily PACKED, which propagates, then confirm.
            // ----------------------------------------------------------------------------------
            {
                const int rl = Weights{}.returnLead;
                AppState app;
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("calc", "Calculus", oStart,
                                                  {{"l", "Laptop", .5}, {"c", "Calculator", .5}})}});
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("dsa", "DSA", oStart + 180,
                                                  {{"l2", "Laptop", .5}, {"p", "iPad", .5}})}});
                const auto calcKey = "calc@" + std::to_string(oDay);
                const auto dsaKey = "dsa@" + std::to_string(oDay);
                const Minute calcEnd = oStart + 60, dsaEnd = oStart + 240;

                // TEST 1 & 2: mark the DAILY Laptop packed; it propagates to both classes' Laptop.
                app.Execute({{"action", "transition_day"},
                             {"now", oStart - 60},
                             {"day", oDay},
                             {"name", "Laptop"},
                             {"target", "PACKED"}});
                // Calculator is only in Calculus; pack it too (single class) so the example matches.
                app.Execute({{"action", "transition_day"},
                             {"now", oStart - 60},
                             {"day", oDay},
                             {"name", "Calculator"},
                             {"target", "PACKED"}});
                app.Execute({{"action", "transition_day"},
                             {"now", oStart - 60},
                             {"day", oDay},
                             {"name", "iPad"},
                             {"target", "PACKED"}});
                {
                    const auto s = app.Save();
                    Check(s["states"][nlohmann::json::array({calcKey, "l"}).dump()] == "PACKED" &&
                              s["states"][nlohmann::json::array({dsaKey, "l2"}).dump()] == "PACKED",
                          "daily PACKED propagates Laptop to both Calculus and DSA");
                }

                // TEST 3: at Calculus return time its Bring Back includes Laptop AND Calculator even
                // though both are still PACKED (no manual PACKED -> BROUGHT performed).
                auto returnPlan = [&](const nlohmann::json &view, const std::string &key) -> nlohmann::json {
                    for (const auto &p : view.at("notifications"))
                        if (p.at("id") == key + ":return")
                            return p;
                    return nullptr;
                };
                {
                    const auto view = app.View(calcEnd - rl);
                    const auto calcReturn = returnPlan(view, calcKey);
                    Check(!calcReturn.is_null() && calcReturn.at("fireAt").get<Minute>() == calcEnd - rl,
                          "Calculus return reminder exists at its own end while items are still PACKED");
                    const std::string body = calcReturn.at("body");
                    Check(body.find("Laptop") != std::string::npos &&
                              body.find("Calculator") != std::string::npos,
                          "Calculus Bring Back includes PACKED Laptop and Calculator");
                    // The event card marks these as returnable via the one-tap class-exit command.
                    for (const auto &ev : view.at("events"))
                        if (ev.at("key") == calcKey)
                            for (const auto &it : ev.at("items"))
                                if (it.at("name") == "Laptop")
                                    Check(it.at("state") == "PACKED" && it.at("canReturn") == true,
                                          "PACKED Calculus Laptop is flagged returnable for class exit");
                }

                // TEST 4 & 5: confirming the Calculus return makes ONLY Calculus Laptop SAFE; DSA
                // Laptop remains PACKED (occurrence isolation).
                app.Execute(
                    {{"action", "return_item"}, {"now", calcEnd}, {"occurrence", calcKey}, {"item", "l"}});
                app.Execute(
                    {{"action", "return_item"}, {"now", calcEnd}, {"occurrence", calcKey}, {"item", "c"}});
                {
                    const auto s = app.Save();
                    Check(s["states"][nlohmann::json::array({calcKey, "l"}).dump()] == "SAFE",
                          "return_item completes Calculus Laptop straight to SAFE from PACKED");
                    Check(s["states"][nlohmann::json::array({calcKey, "c"}).dump()] == "SAFE",
                          "return_item completes Calculus Calculator to SAFE from PACKED");
                    Check(s["states"][nlohmann::json::array({dsaKey, "l2"}).dump()] == "PACKED",
                          "DSA Laptop remains PACKED after confirming the Calculus return");
                }

                // TEST 6: DSA later produces its own Bring Back still containing the PACKED Laptop.
                {
                    const auto view = app.View(dsaEnd - rl);
                    const auto dsaReturn = returnPlan(view, dsaKey);
                    Check(!dsaReturn.is_null() && dsaReturn.at("fireAt").get<Minute>() == dsaEnd - rl,
                          "DSA gets its own return reminder at DSA end");
                    const std::string body = dsaReturn.at("body");
                    Check(body.find("Laptop") != std::string::npos && body.find("iPad") != std::string::npos,
                          "DSA Bring Back still contains its PACKED Laptop and iPad");
                    // Calculus is now fully returned; it must NOT produce a further return reminder.
                    Check(returnPlan(view, calcKey).is_null(),
                          "a fully-returned Calculus no longer produces a return reminder");
                }

                // TEST 7: an ended class with an unconfirmed PACKED item stays visible/actionable.
                {
                    const auto view = app.View(dsaEnd + 30); // both classes ended
                    bool dsaVisible = false;
                    for (const auto &ev : view.at("events"))
                        if (ev.at("key") == dsaKey)
                            dsaVisible = true;
                    Check(dsaVisible,
                          "an ended class with an unconfirmed PACKED item remains actionable in view");
                }

                // return_item is idempotent: confirming an already-SAFE item is a safe no-op.
                app.Execute({{"action", "return_item"},
                             {"now", calcEnd + 5},
                             {"occurrence", calcKey},
                             {"item", "l"}});
                Check(app.Save()["states"][nlohmann::json::array({calcKey, "l"}).dump()] == "SAFE",
                      "repeating return_item on an already-returned item is a safe no-op");

                // return_item is rejected when there is nothing to bring back yet (Needed).
                Rejects(
                    [&] {
                        app.Execute({{"action", "return_item"},
                                     {"now", dsaEnd},
                                     {"occurrence", dsaKey},
                                     {"item", "p2-missing"}});
                    },
                    "return_item on a non-existent item is rejected");
            }

            // TEST 8: BROUGHT / IN_USE / NEEDS_TO_RETURN still behave correctly and return_item
            // also completes them to SAFE (the ordinary lifecycle remains available too).
            {
                AppState app;
                app.Execute({{"action", "save_event"},
                             {"now", oStart - 300},
                             {"event", classEvent("mid", "Midterm", oStart, {{"l", "Laptop", .5}})}});
                const auto midKey = "mid@" + std::to_string(oDay);
                // Ordinary lifecycle still works step by step.
                for (const auto *t : {"PACKED", "BROUGHT", "IN_USE", "NEEDS_TO_RETURN"})
                    app.Execute({{"action", "transition"},
                                 {"now", oStart + 10},
                                 {"occurrence", midKey},
                                 {"item", "l"},
                                 {"target", t}});
                Check(app.Save()["states"][nlohmann::json::array({midKey, "l"}).dump()] == "NEEDS_TO_RETURN",
                      "ordinary PACKED->BROUGHT->IN_USE->NEEDS_TO_RETURN lifecycle still works");
                // A NEEDS_TO_RETURN item is in Bring Back, then return_item completes it to SAFE.
                const auto midView = app.View(oStart + 60 - Weights{}.returnLead);
                bool inReturn = false;
                for (const auto &p : midView["notifications"])
                    if (p.at("id") == midKey + ":return" &&
                        std::string(p.at("body")).find("Laptop") != std::string::npos)
                        inReturn = true;
                Check(inReturn, "a NEEDS_TO_RETURN item is included in its class Bring Back");
                app.Execute(
                    {{"action", "return_item"}, {"now", oStart + 60}, {"occurrence", midKey}, {"item", "l"}});
                Check(app.Save()["states"][nlohmann::json::array({midKey, "l"}).dump()] == "SAFE",
                      "return_item completes a NEEDS_TO_RETURN item to SAFE");
            }

            // Predicate unit coverage: ShouldBringBackFromClass is a PACKED-inclusive superset of
            // ShouldReturn, and the lifecycle graph itself is unchanged (PACKED cannot jump to SAFE).
            {
                Check(ItemLifecycleEngine::ShouldBringBackFromClass(ItemState::Packed) &&
                          ItemLifecycleEngine::ShouldBringBackFromClass(ItemState::Brought) &&
                          ItemLifecycleEngine::ShouldBringBackFromClass(ItemState::InUse) &&
                          ItemLifecycleEngine::ShouldBringBackFromClass(ItemState::NeedsToReturn),
                      "ShouldBringBackFromClass is true for PACKED/BROUGHT/IN_USE/NEEDS_TO_RETURN");
                Check(!ItemLifecycleEngine::ShouldBringBackFromClass(ItemState::Needed) &&
                          !ItemLifecycleEngine::ShouldBringBackFromClass(ItemState::Safe) &&
                          !ItemLifecycleEngine::ShouldBringBackFromClass(ItemState::Forgotten) &&
                          !ItemLifecycleEngine::ShouldBringBackFromClass(ItemState::NotNeeded),
                      "ShouldBringBackFromClass is false for NEEDED/SAFE/FORGOTTEN/NOT_NEEDED");
                Check(!ItemLifecycleEngine::ShouldReturn(ItemState::Packed),
                      "ShouldReturn is unchanged and still excludes PACKED");
                Check(!ItemLifecycleEngine::CanTransition(ItemState::Packed, ItemState::Safe),
                      "the ordinary lifecycle graph still forbids PACKED -> SAFE directly");
            }

            // TEST 11 & 12: daily "mark packed" propagates to all matching same-day requirements
            // but never touches another day's occurrence.
            {
                AppState app;
                Event weekly = classEvent("calc", "Calculus", oStart, {{"l", "Laptop", .5}});
                weekly.repeatDays = 1; // daily -> today AND tomorrow have occurrences
                Event weekly2 = classEvent("dsa", "DSA", oStart + 180, {{"l2", "Laptop", .5}});
                weekly2.repeatDays = 1;
                app.Execute({{"action", "save_event"}, {"now", oStart - 300}, {"event", weekly}});
                app.Execute({{"action", "save_event"}, {"now", oStart - 300}, {"event", weekly2}});
                // Mark the DAILY Laptop packed for today.
                app.Execute({{"action", "transition_day"},
                             {"now", oStart - 60},
                             {"day", oDay},
                             {"name", "laptop"}, // normalized match, different casing on purpose
                             {"target", "PACKED"}});
                const auto saved = app.Save();
                // Both of today's underlying Laptop requirements are now PACKED.
                Check(saved["states"][nlohmann::json::array({"calc@" + std::to_string(oDay), "l"}).dump()] ==
                              "PACKED" &&
                          saved["states"]
                               [nlohmann::json::array({"dsa@" + std::to_string(oDay), "l2"}).dump()] ==
                              "PACKED",
                      "daily mark-packed propagates to every matching same-day requirement");
                // The daily row reads as packed for today.
                const auto todayView = app.View(oStart - 60);
                Check(dailyRow(todayView, "Laptop")["state"] == "PACKED",
                      "the aggregate daily item reads as packed once all today's instances are packed");
                // Tomorrow's corresponding occurrences are untouched (still Needed).
                Check(!saved["states"].contains(
                          nlohmann::json::array({"calc@" + std::to_string(oDay + 1), "l"}).dump()),
                      "tomorrow's Laptop occurrence is not affected by today's daily transition");
                const auto tomorrowView = app.View(oStart - 60 + 1440);
                Check(dailyRow(tomorrowView, "Laptop")["state"] == "NEEDED",
                      "tomorrow's daily Laptop is still Needed");
                // Repeated execution is idempotent/safe (already-packed instances are skipped).
                app.Execute({{"action", "transition_day"},
                             {"now", oStart - 60},
                             {"day", oDay},
                             {"name", "Laptop"},
                             {"target", "PACKED"}});
                Check(app.Save()["states"] == saved["states"],
                      "repeating the daily transition is idempotent");
                // An instance already further along is not regressed by a day-level PACKED request.
                app.Execute({{"action", "transition"},
                             {"now", oStart},
                             {"occurrence", "calc@" + std::to_string(oDay)},
                             {"item", "l"},
                             {"target", "BROUGHT"}});
                app.Execute({{"action", "transition_day"},
                             {"now", oStart},
                             {"day", oDay},
                             {"name", "Laptop"},
                             {"target", "PACKED"}});
                Check(app.Save()["states"]
                                [nlohmann::json::array({"calc@" + std::to_string(oDay), "l"}).dump()] ==
                          "BROUGHT",
                      "a further-along instance is not regressed by a day-level PACKED request");
            }

            // Normalization unit coverage.
            {
                Check(DailyOutingEngine::Normalize(" Laptop ") == "laptop" &&
                          DailyOutingEngine::Normalize("LAPTOP") == "laptop" &&
                          DailyOutingEngine::Normalize("laptop") == "laptop",
                      "Normalize trims whitespace and lower-cases");
            }
        }

        if (argc > 1) {
            std::ifstream config(std::string(argv[1]) + "/reminder_weights.json");
            const auto configured = nlohmann::json::parse(config).get<Weights>();
            AppState validated({}, configured);
            Check(configured.frequency == .3, "config loads");
        }
        std::cout << checks << " checks passed\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
