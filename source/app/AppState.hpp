#pragma once

#include "sim/Engines.hpp"

#include <array>
#include <map>
#include <string>
#include <vector>

namespace memory {

class AppState {
  public:
    explicit AppState(const nlohmann::json &saved = nlohmann::json::object(), const Weights &weights = {},
                      const nlohmann::json &templates = nlohmann::json::object());

    nlohmann::json Execute(const nlohmann::json &command);
    nlohmann::json Save() const;
    nlohmann::json View(Minute now) const;

  private:
    std::vector<Event> mEvents;

    std::map<std::string, ItemState> mStates;
    std::map<std::string, ForgetStats> mProfile;

    Weights mWeights;
    nlohmann::json mTemplates;

    // Existing daily maximum rain probability.
    // Kept for backward compatibility and fallback.
    int mRainProbability = -1;

    // Hour-by-hour weather forecast.
    std::vector<WeatherHour> mHourlyWeather;

    // Time when weather information was fetched.
    Minute mWeatherAt = 0;

    bool mWeatherMock = false;
    Minute mUmbrellaPackedDay = -1;

    std::map<std::string, std::array<TimingBucket, 5>> mTiming;
    bool mAdaptiveTimingEnabled = false;

    nlohmann::json mMemories = nlohmann::json::object();

    ItemState State(const Occurrence &occurrence, const Item &item) const;

    double Risk(const Occurrence &occurrence, const Item &item, Minute now) const;

    void Transition(const std::string &occurrenceKey, const std::string &itemId, ItemState target,
                    Minute now);

    // AdaptiveTiming preferred bring lead for an event (learned when enabled, else fallback).
    int BringLead(const std::string &eventId) const;

    // Derived one-calendar-day outing: every applicable item of `day`'s occurrences folded into a
    // deduplicated (normalized-name) daily list, plus the anchors used to schedule the single daily
    // Bring / end-of-day Bring Back reminders. Purely derived — no Event/Item record is mutated.
    struct DayOuting {
        std::vector<DailyOutingEngine::DailyItem> items;
        bool hasFirstRelevant = false; // an occurrence today still to leave for (end > now)
        Minute firstRelevantStart = 0; // earliest such occurrence's start (leaving time)
        int firstRelevantLead = 0;     // that occurrence's bring lead (drives the daily Bring)
        std::string title;             // representative event title for the day
    };
    DayOuting DailyOuting(Minute day, Minute now) const;

    // Fan a normalized-name daily item transition out to every matching same-day occurrence.
    void TransitionDay(Minute day, const std::string &name, ItemState target, Minute now);

    // Class-exit resolution for ONE occurrence + item: "I am leaving this class and resolving what
    // happened to this item." Resolves that single occurrence's item to `target` (Safe = "got it" or
    // Forgotten = "forgot it") from any state still unresolved for the outing — NEEDED, PACKED,
    // BROUGHT, IN_USE, NEEDS_TO_RETURN — so a user who never touched the app can still resolve it.
    // It preserves the SAFE/returned and FORGOTTEN/forget bookkeeping, never touches other
    // occurrences, and leaves the ordinary lifecycle graph (CanTransition) unchanged for all other
    // flows. `target` must be Safe or Forgotten.
    void ReturnItem(const std::string &occurrenceKey, const std::string &itemId, ItemState target,
                    Minute now);
};

} // namespace memory
