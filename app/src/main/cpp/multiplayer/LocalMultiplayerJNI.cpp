#include <jni.h>
#include <string>
#include <cstring>
#include "LocalMultiplayer.h"

using namespace MelonDSAndroid;

namespace
{
    std::string toStdString(JNIEnv* env, jstring str)
    {
        if (!str)
            return {};
        const char* chars = env->GetStringUTFChars(str, nullptr);
        std::string ret(chars);
        env->ReleaseStringUTFChars(str, chars);
        return ret;
    }

    jobjectArray toStringArray(JNIEnv* env, const std::vector<std::string>& items)
    {
        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray arr = env->NewObjectArray((jsize) items.size(), stringClass, nullptr);
        for (size_t i = 0; i < items.size(); i++)
        {
            jstring s = env->NewStringUTF(items[i].c_str());
            env->SetObjectArrayElement(arr, (jsize) i, s);
            env->DeleteLocalRef(s);
        }
        return arr;
    }

    // fields are separated by a unit separator so player names can contain anything printable
    const char kSep = '\x1F';
}

extern "C"
{
JNIEXPORT jboolean JNICALL
Java_me_magnum_melonds_MelonMultiplayer_startDiscovery(JNIEnv* env, jobject thiz)
{
    return LocalMultiplayer::startDiscovery();
}

JNIEXPORT void JNICALL
Java_me_magnum_melonds_MelonMultiplayer_stopDiscovery(JNIEnv* env, jobject thiz)
{
    LocalMultiplayer::stopDiscovery();
}

JNIEXPORT jboolean JNICALL
Java_me_magnum_melonds_MelonMultiplayer_startHost(JNIEnv* env, jobject thiz, jstring playerName, jint maxPlayers)
{
    return LocalMultiplayer::startHost(toStdString(env, playerName), maxPlayers);
}

JNIEXPORT jboolean JNICALL
Java_me_magnum_melonds_MelonMultiplayer_startClient(JNIEnv* env, jobject thiz, jstring playerName, jstring hostAddress)
{
    return LocalMultiplayer::startClient(toStdString(env, playerName), toStdString(env, hostAddress));
}

JNIEXPORT void JNICALL
Java_me_magnum_melonds_MelonMultiplayer_endSession(JNIEnv* env, jobject thiz)
{
    LocalMultiplayer::endSession();
}

JNIEXPORT jboolean JNICALL
Java_me_magnum_melonds_MelonMultiplayer_isSessionActive(JNIEnv* env, jobject thiz)
{
    return LocalMultiplayer::isSessionActive();
}

JNIEXPORT jboolean JNICALL
Java_me_magnum_melonds_MelonMultiplayer_isHost(JNIEnv* env, jobject thiz)
{
    return LocalMultiplayer::isHost();
}

// each entry: address, name, numPlayers, maxPlayers
JNIEXPORT jobjectArray JNICALL
Java_me_magnum_melonds_MelonMultiplayer_getDiscoveredSessionsInternal(JNIEnv* env, jobject thiz)
{
    std::vector<std::string> out;
    for (const auto& s : LocalMultiplayer::getDiscoveredSessions())
    {
        out.push_back(s.address + kSep + s.name + kSep + std::to_string(s.numPlayers) + kSep + std::to_string(s.maxPlayers));
    }
    return toStringArray(env, out);
}

// each entry: id, name, status, isLocal, pingMs
JNIEXPORT jobjectArray JNICALL
Java_me_magnum_melonds_MelonMultiplayer_getPlayersInternal(JNIEnv* env, jobject thiz)
{
    auto stats = LocalMultiplayer::getStats();
    std::vector<std::string> out;
    for (const auto& p : LocalMultiplayer::getPlayers())
    {
        std::string name(p.Name, strnlen(p.Name, sizeof(p.Name)));
        melonDS::u32 ping = p.IsLocalPlayer ? 0 : stats.PeerPing[p.ID & 0xF];
        out.push_back(std::to_string(p.ID) + kSep + name + kSep + std::to_string((int) p.Status) + kSep +
                      (p.IsLocalPlayer ? "1" : "0") + kSep + std::to_string(ping));
    }
    return toStringArray(env, out);
}

// exchanges, replyTimeouts, staleDrops, duplicateDrops, redundantSent,
// avgExchangeUs, maxExchangeMs, currentTimeoutMs, redundantPeersMask
JNIEXPORT jlongArray JNICALL
Java_me_magnum_melonds_MelonMultiplayer_getStatsInternal(JNIEnv* env, jobject thiz)
{
    auto s = LocalMultiplayer::getStats();
    jlong values[9] = {
        s.Exchanges, s.ReplyTimeouts, s.StaleDrops, s.DuplicateDrops, s.RedundantSent,
        s.AvgExchangeUs, s.MaxExchangeMs, s.CurrentTimeoutMs, s.RedundantPeers,
    };
    jlongArray arr = env->NewLongArray(9);
    env->SetLongArrayRegion(arr, 0, 9, values);
    return arr;
}

JNIEXPORT void JNICALL
Java_me_magnum_melonds_MelonMultiplayer_setTuning(JNIEnv* env, jobject thiz, jboolean adaptive, jint minTimeoutMs, jint maxTimeoutMs, jboolean redundancy)
{
    melonDS::WifiLAN::Tuning t;
    t.Adaptive = adaptive;
    t.MinTimeout = minTimeoutMs;
    t.MaxTimeout = maxTimeoutMs;
    t.Redundancy = redundancy;
    LocalMultiplayer::setTuning(t);
}
}
