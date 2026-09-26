#include "sim/ImportEngine.hpp"
#include "sim/Engines.hpp"
#include <algorithm>
#include <array>
#include <chrono>
#include <regex>
#include <stdexcept>
namespace memory {
namespace {
Minute Time(const std::string &value) {
    if (!std::regex_match(value, std::regex("([01][0-9]|2[0-3]):[0-5][0-9]")))
        throw std::invalid_argument("Invalid extracted time. Enter this event manually.");
    return std::stoi(value.substr(0, 2)) * 60 + std::stoi(value.substr(3, 2));
}
Minute Date(const std::string &value) {
    if (!std::regex_match(value, std::regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        throw std::invalid_argument("Invalid extracted date.");
    const std::chrono::year_month_day date{
        std::chrono::year(std::stoi(value.substr(0, 4))),
        std::chrono::month(static_cast<unsigned>(std::stoi(value.substr(5, 2)))),
        std::chrono::day(static_cast<unsigned>(std::stoi(value.substr(8, 2))))};
    if (!date.ok())
        throw std::invalid_argument("Invalid extracted date.");
    return std::chrono::sys_days(date).time_since_epoch().count();
}
} // namespace
std::vector<Event> ImportEngine::Timetable(const nlohmann::json &data, Minute referenceDay,
                                           const std::string &importId) {
    const std::array<std::string, 7> days{"Monday", "Tuesday",  "Wednesday", "Thursday",
                                          "Friday", "Saturday", "Sunday"};
    if (!data.at("events").is_array() || data.at("events").empty() || data.at("events").size() > 50)
        throw std::invalid_argument("No valid timetable was found.");
    std::vector<Event> events;
    for (const auto &raw : data.at("events")) {
        const auto day = raw.at("day").get<std::string>();
        const auto found = std::find(days.begin(), days.end(), day);
        if (found == days.end())
            throw std::invalid_argument("Invalid extracted weekday.");
        const auto offset = (std::distance(days.begin(), found) - (referenceDay + 3) % 7 + 7) % 7;
        Event event;
        event.id = importId + "-" + std::to_string(events.size());
        event.title = raw.at("title");
        event.location = raw.value("location", "");
        event.course = raw.value("course", "");
        event.repeatDays = 7;
        event.start = (referenceDay + offset) * 1440 + Time(raw.at("start_time"));
        event.end = (referenceDay + offset) * 1440 + Time(raw.at("end_time"));
        EventEngine::Validate(event);
        events.push_back(event);
    }
    return events;
}
Event ImportEngine::Instruction(const nlohmann::json &data, Event event, const std::string &importId) {
    const auto day = Date(data.at("date"));
    if (RecurrenceEngine::Generate(event, day, day).empty())
        throw std::invalid_argument(
            "The instruction date does not match this class. Edit the event or use manual entry.");
    int index = 0;
    for (const auto &name : data.at("required_items")) {
        event.items.push_back(
            {importId + "-item-" + std::to_string(index++), name.get<std::string>(), .8, day});
    }
    for (const auto &raw : data.at("tasks")) {
        event.tasks.push_back({importId + "-task-" + std::to_string(index++), raw.at("title"),
                               raw.value("duration", 60), day * 1440 + event.start % 1440, false});
    }
    EventEngine::Validate(event);
    return event;
}
} // namespace memory
