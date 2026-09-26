#pragma once
#include "services/IBackend.hpp"
namespace memory {
class FakeBackend final : public IBackend {
  public:
    bool offline = false;
    ServiceResult Extract(const std::string &kind, const std::string &input) override;
    ServiceResult Weather() override;
};
} // namespace memory
