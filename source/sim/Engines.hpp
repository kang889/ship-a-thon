#pragma once
#include "core/Models.hpp"
#include <array>
#include <optional>

namespace memory {
class EventEngine {
  public:
    static void Validate(const Event &event);
};
class RecurrenceEngine {
  public:
    static std::vector<Occurrence> Generate(const Event &event, Minute firstDay, Minute lastDay);
};
class TimetableEngine {
  public:
    static std::vector<Occurrence> Between(const std::vector<Event> &events, Minute firstDay, Minute lastDay);
    static bool Overlaps(const Occurrence &a, const Occurrence &b);
};
class ItemLifecycleEngine {
  public:
    static bool CanTransition(ItemState from, ItemState to);
    // True while an item is mid-lifecycle and still owed a return in the ordinary sense
    // (Brought / InUse / NeedsToReturn). Unchanged; drives the generic "awaiting return" notion.
    static bool ShouldReturn(ItemState state);
    // Class-exit semantics only. PackBack should still help a user who never touched the app, so an
    // item still attached to a class must be surfaced in that class's Bring Back and remain
    // actionable until it is resolved — regardless of whether the user ever marked it PACKED. It is
    // true for anything still unresolved for the outing: NEEDED, PACKED, BROUGHT, IN_USE,
    // NEEDS_TO_RETURN. It is false for the explicit opt-out (NOT_NEEDED) and for already-resolved
    // outcomes (SAFE, FORGOTTEN). Used ONLY for per-class Bring Back listing, keeping an ended
    // occurrence actionable, and the Android "can resolve at class exit" flag. It does NOT change
    // the lifecycle graph (CanTransition is unchanged).
    static bool ShouldResolveAtClassExit(ItemState state);
};
class ForgetRiskEngine {
  public:
    static double Score(const ForgetStats &stats, double importance, bool unusual, double urgency,
                        const Weights &weights);
};
class ReminderDecisionEngine {
  public:
    static Priority Classify(double risk, const Weights &weights);
};
// Per-occurrence reminder inputs. The caller supplies the deterministic risk,
// timing and lifecycle results; the engine owns only how they become plans.
struct OccurrenceReminders {
    std::string key, title;
    // Comma-joined item names, empty when no item currently needs the reminder.
    std::string bringItems, backItems;
    std::string bringPrefix, backPrefix;
    Minute bringFireAt = 0, returnFireAt = 0;
    Priority bringPriority = Priority::Low, returnPriority = Priority::Low;
};
// Weather-driven "bring an umbrella" reminder input.
struct UmbrellaReminder {
    bool active = false;
    Minute day = 0, fireAt = 0;
    std::string body;
};
// Turns deterministic reminder inputs into the ordered notification plan.
// Owns the gating rules, body composition, plan identity and ordering that
// previously lived inside AppState::View. It performs no risk, timing or
// lifecycle decisions of its own.
class NotificationPlanEngine {
  public:
    static std::vector<Reminder> Build(const std::vector<OccurrenceReminders> &occurrences,
                                       const UmbrellaReminder &umbrella, Minute now);
};
class PrepScheduler {
  public:
    static std::optional<Minute> FindSlot(Minute now, Minute deadline, int duration,
                                          const std::vector<Occurrence> &busy);
};
// ----------------------------------------------------
// Daily outing aggregation
// ----------------------------------------------------
// PackBack treats one calendar day as a single outing. Every applicable item required by any of
// that day's occurrences is folded into a deduplicated daily list, keyed by NORMALIZED name
// (trimmed, case-insensitive) so "Laptop", "laptop" and " Laptop " are one physical thing that
// only ever appears once. This is a DERIVED view over the underlying Event/Item records — the
// records themselves are never merged or mutated. Cloud sync still carries normal events with
// their normal item arrays; the day outing is re-derived locally on every device.
class DailyOutingEngine {
  public:
    // One underlying (occurrence, item) requirement fed into the aggregation. The caller
    // (AppState) supplies the already-computed lifecycle state and priority so this engine owns
    // only the deduplication/union rules, never risk/timing/lifecycle decisions of its own.
    struct Contribution {
        std::string occurrenceKey; // occurrence this requirement belongs to
        std::string itemId;        // underlying item UUID (differs per event)
        std::string name;          // original display spelling as authored
        std::string title;         // event title requiring it (for "why" metadata)
        ItemState state = ItemState::Needed;
        Priority priority = Priority::Low;
    };
    // A single deduplicated daily item. `count` is how many of the day's occurrences require it;
    // `titles` are the distinct event titles that require it (first-seen order). `state` is the
    // representative aggregate state (see DailyState); `priority` is the maximum among the
    // underlying requirements. `sources` retains every underlying (occurrence,item) pair so a
    // day-level action can fan out to all of them.
    struct DailyItem {
        std::string normalized; // key: trimmed + lower-cased name
        std::string name;       // preserved display name (first encountered spelling)
        ItemState state = ItemState::Needed;
        Priority priority = Priority::Low;
        int count = 0;
        std::vector<std::string> titles;
        std::vector<std::pair<std::string, std::string>> sources; // (occurrenceKey, itemId)
    };
    // Trim surrounding whitespace and lower-case for case-insensitive comparison.
    static std::string Normalize(const std::string &name);
    // Fold contributions (already filtered for the target day) into deduplicated daily items,
    // preserving first-seen order of distinct normalized names.
    static std::vector<DailyItem> Aggregate(const std::vector<Contribution> &contributions);
    // The representative daily state: the MINIMUM progress across the underlying requirements, so
    // a daily item only reads as "packed" once every applicable underlying requirement is at least
    // packed. Terminal/So-far-along instances never drag the day backwards below Needed.
    static ItemState DailyState(const std::vector<ItemState> &states);
};
struct TimingBucket {
    int attempts = 0, successes = 0;
};
NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE_WITH_DEFAULT(TimingBucket, attempts, successes)
class AdaptiveTiming {
  public:
    static int Bucket(int lead);
    static int Preferred(const std::array<TimingBucket, 5> &history, int minimumSamples, int minimumLead,
                         int fallback);
};
class ContextEngine {
  public:
    static int MaxRainProbability(const std::vector<WeatherHour> &forecast, Minute start, Minute end);
    static bool CoversWindow(const std::vector<WeatherHour> &forecast, Minute start, Minute end);

    static bool SuggestUmbrella(int rainProbability, bool travelling, const Weights &weights);
};
} // namespace memory
