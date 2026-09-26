#pragma once
#include "sim/Engines.hpp"
#include <map>

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
    int mRainProbability = -1;
    Minute mWeatherAt = 0;
    bool mWeatherMock = false;
    std::map<std::string, std::array<TimingBucket, 5>> mTiming;
    nlohmann::json mMemories = nlohmann::json::object();
    ItemState State(const Occurrence &occurrence, const Item &item) const;
    double Risk(const Occurrence &occurrence, const Item &item, Minute now) const;
    void Transition(const std::string &occurrenceKey, const std::string &itemId, ItemState target,
                    Minute now);
};
} // namespace memory
