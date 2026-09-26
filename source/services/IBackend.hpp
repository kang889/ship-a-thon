#pragma once
#include <nlohmann/json.hpp>
#include <string>

namespace memory {
struct ServiceResult {
    bool ok = false;
    nlohmann::json data;
    std::string error;
};
class IBackend {
  public:
    virtual ~IBackend() = default;
    virtual ServiceResult Extract(const std::string &kind, const std::string &input) = 0;
    virtual ServiceResult Weather() = 0;
};
} // namespace memory
