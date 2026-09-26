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
    static bool ShouldReturn(ItemState state);
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
class PrepScheduler {
  public:
    static std::optional<Minute> FindSlot(Minute now, Minute deadline, int duration,
                                          const std::vector<Occurrence> &busy);
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
    static bool SuggestUmbrella(int rainProbability, bool travelling, const Weights &weights);
};
} // namespace memory
