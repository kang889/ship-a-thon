#include "sim/Engines.hpp"
#include <algorithm>
#include <cmath>
#include <set>
#include <stdexcept>

namespace memory {
void EventEngine::Validate(const Event &event) {
    if (event.id.empty() || event.title.empty() || event.title.size() > 200 || event.start < 0 ||
        event.end <= event.start || event.end - event.start > 1440 ||
        (event.repeatDays != 0 && event.repeatDays != 1 && event.repeatDays != 7) ||
        (event.untilDay != -1 && event.untilDay < event.start / 1440)) {
        throw std::invalid_argument("Enter a title, valid times, and none/daily/weekly recurrence.");
    }
    std::set<std::string> ids;
    for (const auto &item : event.items) {
        if (item.id.empty() || item.name.empty() || !ids.insert(item.id).second ||
            !std::isfinite(item.importance) || item.importance < 0 || item.importance > 1 ||
            item.onlyDay < -1)
            throw std::invalid_argument("Items need unique IDs, names, and importance between 0 and 1.");
    }
    ids.clear();
    for (const auto &task : event.tasks) {
        if (task.id.empty() || task.title.empty() || !ids.insert(task.id).second || task.duration < 1 ||
            task.duration > 1440 || task.deadline < 0)
            throw std::invalid_argument("Tasks need unique IDs, titles, and a duration of 1–1440 minutes.");
    }
}
std::vector<Occurrence> RecurrenceEngine::Generate(const Event &event, Minute firstDay, Minute lastDay) {
    EventEngine::Validate(event);
    if (firstDay < 0 || lastDay < firstDay || lastDay - firstDay > 366)
        throw std::invalid_argument("Query at most 367 days.");
    std::vector<Occurrence> result;
    const auto baseDay = event.start / 1440;
    for (auto day = std::max(baseDay, firstDay); day <= lastDay; ++day) {
        const auto offset = day - baseDay;
        if ((event.untilDay >= 0 && day > event.untilDay) ||
            (event.repeatDays == 0 ? offset != 0 : offset % event.repeatDays != 0))
            continue;
        result.push_back({event, event.start + offset * 1440, event.end + offset * 1440,
                          event.id + "@" + std::to_string(day)});
    }
    return result;
}
std::vector<Occurrence> TimetableEngine::Between(const std::vector<Event> &events, Minute firstDay,
                                                 Minute lastDay) {
    std::vector<Occurrence> result;
    for (const auto &event : events) {
        const auto occurrences = RecurrenceEngine::Generate(event, firstDay, lastDay);
        result.insert(result.end(), occurrences.begin(), occurrences.end());
    }
    std::sort(result.begin(), result.end(), [](const auto &a, const auto &b) {
        return a.start != b.start ? a.start < b.start : a.key < b.key;
    });
    return result;
}
bool TimetableEngine::Overlaps(const Occurrence &a, const Occurrence &b) {
    return a.start < b.end && b.start < a.end;
}
bool ItemLifecycleEngine::CanTransition(ItemState from, ItemState to) {
    if (from == to || from == ItemState::Safe || from == ItemState::Forgotten || from == ItemState::NotNeeded)
        return false;
    if (to == ItemState::Forgotten)
        return true;
    if (to == ItemState::NotNeeded)
        return from == ItemState::Needed;
    switch (from) {
    case ItemState::Needed:
        return to == ItemState::Packed;
    case ItemState::Packed:
        return to == ItemState::Brought;
    case ItemState::Brought:
        return to == ItemState::InUse || to == ItemState::NeedsToReturn;
    case ItemState::InUse:
        return to == ItemState::NeedsToReturn;
    case ItemState::NeedsToReturn:
        return to == ItemState::Safe;
    default:
        return false;
    }
}
bool ItemLifecycleEngine::ShouldReturn(ItemState state) {
    return state == ItemState::Brought || state == ItemState::InUse || state == ItemState::NeedsToReturn;
}
double ForgetRiskEngine::Score(const ForgetStats &stats, double importance, bool unusual, double urgency,
                               const Weights &w) {
    const auto count = stats.forgotten + stats.returned;
    const double frequency = count > 0 ? static_cast<double>(stats.forgotten) / count : 0;
    return std::clamp(w.frequency * frequency + w.association +
                          w.importance * std::clamp(importance, 0.0, 1.0) +
                          w.unusualness * (unusual ? 1 : 0) + w.urgency * std::clamp(urgency, 0.0, 1.0),
                      0.0, 1.0);
}
Priority ReminderDecisionEngine::Classify(double risk, const Weights &w) {
    if (risk >= w.veryHigh)
        return Priority::VeryHigh;
    if (risk >= w.high)
        return Priority::High;
    if (risk >= w.medium)
        return Priority::Medium;
    return Priority::Low;
}
std::optional<Minute> PrepScheduler::FindSlot(Minute now, Minute deadline, int duration,
                                              const std::vector<Occurrence> &busy) {
    if (duration <= 0 || deadline <= now)
        return std::nullopt;
    auto sorted = busy;
    std::sort(sorted.begin(), sorted.end(), [](const auto &a, const auto &b) { return a.start < b.start; });
    auto candidate = now;
    for (const auto &event : sorted) {
        if (event.end <= candidate)
            continue;
        if (candidate + duration <= std::min(event.start, deadline))
            return candidate;
        if (event.start < candidate + duration)
            candidate = std::max(candidate, event.end);
        if (candidate + duration > deadline)
            return std::nullopt;
    }
    return candidate + duration <= deadline ? std::optional<Minute>(candidate) : std::nullopt;
}
int AdaptiveTiming::Bucket(int lead) {
    if (lead > 120)
        return 0;
    if (lead >= 60)
        return 1;
    if (lead >= 30)
        return 2;
    if (lead >= 15)
        return 3;
    return 4;
}
int AdaptiveTiming::Preferred(const std::array<TimingBucket, 5> &history, int minimumSamples, int minimumLead,
                              int fallback) {
    constexpr std::array<int, 5> leads{150, 90, 45, 20, 10};
    int best = std::max(minimumLead, fallback);
    double bestRate = -1;
    for (std::size_t i = 0; i < history.size(); ++i) {
        if (history[i].attempts < minimumSamples || leads[i] < minimumLead)
            continue;
        const auto rate = static_cast<double>(history[i].successes) / history[i].attempts;
        if (rate > bestRate) {
            best = leads[i];
            bestRate = rate;
        }
    }
    return best;
}
bool ContextEngine::SuggestUmbrella(int rainProbability, bool travelling, const Weights &weights) {
    return travelling && rainProbability >= weights.rainThreshold && rainProbability <= 100;
}
} // namespace memory
