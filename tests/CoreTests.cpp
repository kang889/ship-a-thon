#include "app/AppState.hpp"
#include "services/FakeBackend.hpp"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
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
        const Minute nowWeather = start - 60;
        auto later = event;
        later.id = "last";
        later.title = "Evening class";
        later.start = start + 240;
        later.end = later.start + 60;
        AppState forecastApp;
        forecastApp.Execute({{"action", "save_event"}, {"now", nowWeather}, {"event", event}});
        forecastApp.Execute({{"action", "save_event"}, {"now", nowWeather}, {"event", later}});
        auto forecast = [&](int chance, Minute rainAt) {
            nlohmann::json hours = nlohmann::json::array();
            for (Minute hour = nowWeather; hour <= later.start; hour += 60)
                hours.push_back({{"time", hour}, {"rain_probability", hour == rainAt ? chance : 0}});
            return forecastApp
                .Execute({{"action", "weather"}, {"now", nowWeather}, {"mock", true}, {"hourly", hours}})
                .at("view");
        };
        auto noUmbrella = forecast(60, start + 120);
        Check(noUmbrella["umbrella"] == false && noUmbrella["todayBring"][0]["name"] == "Charger",
              "exactly 60 does not add umbrella to bring list");
        auto decision = forecast(61, start + 120);
        Check(decision["umbrella"] == true && decision["weatherLastEventStart"] == later.start,
              "rain before last event triggers");
        Check(decision["todayBring"][0]["name"] == "Umbrella" &&
                  decision["todayBring"][1]["eventTitle"] == "Programming Lab" &&
                  decision["todayBring"].back()["eventTitle"] == "Evening class",
              "umbrella precedes event-labelled daily items");
        Check(std::any_of(decision["notifications"].begin(), decision["notifications"].end(),
                          [](const auto &plan) { return plan.at("id") == "weather@20000:bring"; }),
              "rain schedules bring umbrella reminder");
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
        Check(forecast(90, later.start)["umbrella"] == false, "rain after last event ignored");
        Check(forecast(61, nowWeather)["umbrella"] == true, "current forecast hour included");
        Check(forecastApp.View(nowWeather + 15)["umbrella"] == true, "current partial hour included");
        Check(forecastApp.View(later.start)["umbrella"] == false, "last event has begun");
        Check(forecastApp.View(nowWeather + 61)["weatherChecked"] == false, "stale forecast expires");
        Check(AppState().View(nowWeather)["umbrella"] == false, "no event today");
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
