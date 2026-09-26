#pragma once
#include "core/Models.hpp"
namespace memory {
class ImportEngine {
  public:
    static std::vector<Event> Timetable(const nlohmann::json &data, Minute referenceDay,
                                        const std::string &importId);
    static Event Instruction(const nlohmann::json &data, Event event, const std::string &importId);
};
} // namespace memory
