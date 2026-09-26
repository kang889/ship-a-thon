#include "services/FakeBackend.hpp"
namespace memory {
ServiceResult FakeBackend::Extract(const std::string &kind, const std::string &input) {
    if (offline)
        return {false, {}, "Offline. You can still enter events manually."};
    if (input.empty())
        return {false, {}, "Select an image or enter text."};
    if (kind == "timetable")
        return {true,
                {{"mock", true},
                 {"events",
                  {{{"title", "Programming Lab"},
                    {"day", "Monday"},
                    {"start_time", "14:00"},
                    {"end_time", "16:00"},
                    {"location", "Room 3-5"}}}}},
                {}};
    if (kind == "instruction")
        return {true,
                {{"mock", true},
                 {"required_items", {"Scientific calculator"}},
                 {"tasks", {{{"title", "Complete Questions 1–8"}, {"duration", 60}}}}},
                {}};
    return {false, {}, "Unknown extraction kind."};
}
ServiceResult FakeBackend::Weather() {
    return offline ? ServiceResult{false, {}, "Weather unavailable."}
                   : ServiceResult{true, {{"mock", true}, {"rain_probability", 75}}, {}};
}
} // namespace memory
