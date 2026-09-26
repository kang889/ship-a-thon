#include "app/AppState.hpp"
#include "services/FakeBackend.hpp"
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
        Check(ContextEngine::SuggestUmbrella(75, true, weights), "rain context");
        Check(!ContextEngine::SuggestUmbrella(75, false, weights), "travel required");
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
