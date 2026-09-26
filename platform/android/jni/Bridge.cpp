#include "app/AppState.hpp"
#include <jni.h>
#include <string>

namespace {
std::string Read(JNIEnv *env, jbyteArray bytes) {
    if (!bytes)
        throw std::invalid_argument("Missing input.");
    const auto length = env->GetArrayLength(bytes);
    if (length > 4 * 1024 * 1024)
        throw std::invalid_argument("Saved state exceeds 4 MB.");
    std::string text(static_cast<std::size_t>(length), '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(text.data()));
    return text;
}
} // namespace
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_studentmemory_copilot_NativeCore_transact(
    JNIEnv *env, jobject, jbyteArray saved, jbyteArray command, jbyteArray config, jbyteArray templates) {
    std::string result;
    try {
        const auto state = Read(env, saved);
        memory::AppState app(nlohmann::json::parse(state),
                             nlohmann::json::parse(Read(env, config)).get<memory::Weights>(),
                             nlohmann::json::parse(Read(env, templates)));
        result = app.Execute(nlohmann::json::parse(Read(env, command))).dump();
    } catch (const std::exception &error) {
        result = nlohmann::json({{"ok", false}, {"error", error.what()}})
                     .dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
    } catch (...) {
        result = R"({"ok":false,"error":"The native core could not complete that action."})";
    }
    const auto output = env->NewByteArray(static_cast<jsize>(result.size()));
    if (output)
        env->SetByteArrayRegion(output, 0, static_cast<jsize>(result.size()),
                                reinterpret_cast<const jbyte *>(result.data()));
    return output;
}
