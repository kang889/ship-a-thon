#pragma once

#include <cstdint>
#include <nlohmann/json.hpp>
#include <string>
#include <vector>

namespace memory {

using Minute = std::int64_t;

// Civil minutes since 1970-01-01.
// Platform adapters map these to the device zone.
// Recurrence retains local class time across daylight-saving changes.

enum class ItemState {
    Needed,
    Packed,
    Brought,
    InUse,
    NeedsToReturn,
    Safe,
    Forgotten,
    NotNeeded
};

NLOHMANN_JSON_SERIALIZE_ENUM(
    ItemState,
    {
        {ItemState::Needed, "NEEDED"},
        {ItemState::Packed, "PACKED"},
        {ItemState::Brought, "BROUGHT"},
        {ItemState::InUse, "IN_USE"},
        {ItemState::NeedsToReturn, "NEEDS_TO_RETURN"},
        {ItemState::Safe, "SAFE"},
        {ItemState::Forgotten, "FORGOTTEN"},
        {ItemState::NotNeeded, "NOT_NEEDED"}
    }
)

struct Item {
    std::string id, name;
    double importance = 0.5;

    // -1 applies to every occurrence;
    // otherwise only the given civil day.
    Minute onlyDay = -1;
};

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE_WITH_DEFAULT(
    Item,
    id,
    name,
    importance,
    onlyDay
)

struct Task {
    std::string id, title;
    int duration = 60;
    Minute deadline = 0;
    bool completed = false;
};

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE_WITH_DEFAULT(
    Task,
    id,
    title,
    duration,
    deadline,
    completed
)

struct Event {
    std::string id, title, course, type = "class", location, notes;
    Minute start = 0, end = 0;
    int repeatDays = 0;
    Minute untilDay = -1;

    std::vector<Item> items;
    std::vector<Task> tasks;
};

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE_WITH_DEFAULT(
    Event,
    id,
    title,
    course,
    type,
    location,
    notes,
    start,
    end,
    repeatDays,
    untilDay,
    items,
    tasks
)

struct Occurrence {
    Event event;
    Minute start = 0, end = 0;
    std::string key;
};

struct ForgetStats {
    int forgotten = 0;
    int returned = 0;
    int ignored = 0;
    int successful = 0;

    Minute lastForgotten = 0;
    Minute lastSuccess = 0;
};

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE_WITH_DEFAULT(
    ForgetStats,
    forgotten,
    returned,
    ignored,
    successful,
    lastForgotten,
    lastSuccess
)


// ----------------------------------------------------
// Weather
// ----------------------------------------------------

// Forecast for one hour.
struct WeatherHour {
    Minute time = 0;
    int rainProbability = -1;
};

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE_WITH_DEFAULT(
    WeatherHour,
    time,
    rainProbability
)


struct Weights {
    double frequency = .30;
    double association = .25;
    double importance = .20;
    double unusualness = .15;
    double urgency = .10;

    double medium = .35;
    double high = .60;
    double veryHigh = .80;

    int bringLead = 60;
    int returnLead = 10;

    // Recommend an umbrella if rain probability while
    // the student may be outside reaches this threshold.
    int rainThreshold = 60;

    int minimumSamples = 5;
};

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE_WITH_DEFAULT(
    Weights,
    frequency,
    association,
    importance,
    unusualness,
    urgency,
    medium,
    high,
    veryHigh,
    bringLead,
    returnLead,
    rainThreshold,
    minimumSamples
)

enum class Priority {
    Low,
    Medium,
    High,
    VeryHigh
};

struct Reminder {
    std::string id, title, body;
    Minute fireAt = 0;
    Priority priority = Priority::Low;
};

struct StudentDay {
    std::vector<Occurrence> events;
    std::vector<Reminder> reminders;
};

} // namespace memory