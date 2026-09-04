#include <jni.h>
#include <pthread.h>
#include <array>
#include <string>
#include <unistd.h>
#include <sys/socket.h>
#include <cstring>
#include <cerrno>
#include <android/log.h>

#define LOG_TAG "BluetoothNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Android NDK does not ship BlueZ headers; define what we need.
#ifndef AF_BLUETOOTH
#define AF_BLUETOOTH 31
#endif
#ifndef BTPROTO_L2CAP
#define BTPROTO_L2CAP 0
#endif

typedef struct {
    uint8_t b[6];
} __attribute__((packed)) bdaddr_t;

static const bdaddr_t BDADDR_ANY_VAL = {{0, 0, 0, 0, 0, 0}};

struct sockaddr_l2 {
    uint8_t  l2_family;
    uint8_t  l2_reserved;
    uint16_t l2_psm;
    bdaddr_t l2_bdaddr;
    uint16_t l2_cid;
    uint8_t  l2_bdaddr_type;
};

static inline int str2ba(const char *str, bdaddr_t *ba) {
    int i;
    for (i = 5; i >= 0; i--, str += 3) {
        unsigned int b;
        if (sscanf(str, "%2x", &b) != 1) return -1;
        ba->b[i] = (uint8_t) b;
    }
    return 0;
}

static inline uint16_t htobs(uint16_t v) {
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
    return v;
#else
    return __builtin_bswap16(v);
#endif
}

static JavaVM* gVm = nullptr;

template<size_t N>
constexpr auto encryptString(const char (&str)[N], char key) {
    std::array<char, N> encrypted{};
    for (size_t i = 0; i < N; i++) {
        encrypted[i] = str[i] ^ key;
    }
    return encrypted;
}

template<size_t N>
static std::string decryptString(const std::array<char, N>& encrypted, char key) {
    std::string result(N - 1, '\0');
    for (size_t i = 0; i < N - 1; i++) {
        result[i] = encrypted[i] ^ key;
    }
    return result;
}

#define ENC(str) encryptString(str, 0x47)
#define DEC(arr) decryptString(arr, 0x47).c_str()

__attribute__((visibility("hidden")))
static JavaVM* getVm() { return gVm; }

__attribute__((visibility("default")))
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    gVm = vm;

    auto fn = [](void*) -> void* {
        constexpr auto c1 = ENC("dalvik/system/VMRuntime");
        constexpr auto c2 = ENC("getRuntime");
        constexpr auto c3 = ENC("()Ldalvik/system/VMRuntime;");
        constexpr auto c4 = ENC("setHiddenApiExemptions");
        constexpr auto c5 = ENC("([Ljava/lang/String;)V");
        constexpr auto c6 = ENC("java/lang/String");
        constexpr auto c7 = ENC("Landroid/bluetooth/BluetoothSocket;");
        constexpr auto c8 = ENC("Landroid/bluetooth/BluetoothDevice;");

        JNIEnv* env;
        getVm()->AttachCurrentThread(&env, nullptr);

        jclass vmRuntime = env->FindClass(DEC(c1));
        jmethodID getRuntime = env->GetStaticMethodID(vmRuntime, DEC(c2), DEC(c3));
        jmethodID setExemptions = env->GetMethodID(vmRuntime, DEC(c4), DEC(c5));

        jobject runtime = env->CallStaticObjectMethod(vmRuntime, getRuntime);
        jobjectArray prefixes = env->NewObjectArray(
                2, env->FindClass(DEC(c6)), nullptr);
        env->SetObjectArrayElement(prefixes, 0, env->NewStringUTF(DEC(c7)));
        env->SetObjectArrayElement(prefixes, 1, env->NewStringUTF(DEC(c8)));

        env->CallVoidMethod(runtime, setExemptions, prefixes);
        getVm()->DetachCurrentThread();
        return nullptr;
    };

    pthread_t t;
    pthread_create(&t, nullptr, fn, nullptr);
    pthread_join(t, nullptr);
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_me_kavishdevar_librepods_wear_bluetooth_BluetoothNative_createNativeL2capSocket(
        JNIEnv *env, jclass /* clazz */, jstring address, jint psm) {
    const char *addr = env->GetStringUTFChars(address, nullptr);
    LOGI("createNativeL2capSocket: addr=%s psm=0x%x", addr, psm);

    int sock = socket(AF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP);
    if (sock < 0) {
        LOGE("socket(AF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP) failed: errno=%d (%s)", errno, strerror(errno));
        env->ReleaseStringUTFChars(address, addr);
        return nullptr;
    }
    LOGD("socket() succeeded, fd=%d", sock);

    struct sockaddr_l2 localAddr{};
    memset(&localAddr, 0, sizeof(localAddr));
    localAddr.l2_family = AF_BLUETOOTH;
    localAddr.l2_bdaddr = BDADDR_ANY_VAL;
    localAddr.l2_psm = 0;
    if (bind(sock, (struct sockaddr *) &localAddr, sizeof(localAddr)) < 0) {
        LOGE("bind() failed: errno=%d (%s)", errno, strerror(errno));
        close(sock);
        env->ReleaseStringUTFChars(address, addr);
        return nullptr;
    }
    LOGD("bind() succeeded");

    struct sockaddr_l2 remoteAddr{};
    memset(&remoteAddr, 0, sizeof(remoteAddr));
    remoteAddr.l2_family = AF_BLUETOOTH;
    remoteAddr.l2_psm = htobs((uint16_t) psm);
    str2ba(addr, &remoteAddr.l2_bdaddr);

    LOGI("connect() to %s PSM 0x%04x ...", addr, psm);
    if (connect(sock, (struct sockaddr *) &remoteAddr, sizeof(remoteAddr)) < 0) {
        LOGE("connect() failed: errno=%d (%s)", errno, strerror(errno));
        close(sock);
        env->ReleaseStringUTFChars(address, addr);
        return nullptr;
    }
    LOGI("connect() succeeded!");

    env->ReleaseStringUTFChars(address, addr);

    int fd = dup(sock);
    close(sock);
    LOGI("Returning ParcelFileDescriptor with fd=%d", fd);

    jclass pfdClass = env->FindClass("android/os/ParcelFileDescriptor");
    jmethodID ctor = env->GetMethodID(pfdClass, "<init>", "(Ljava/io/FileDescriptor;)V");
    jclass fdClass = env->FindClass("java/io/FileDescriptor");
    jmethodID fdCtor = env->GetMethodID(fdClass, "<init>", "()V");
    jobject fdObj = env->NewObject(fdClass, fdCtor);

    jfieldID fdField = env->GetFieldID(fdClass, "fd", "I");
    env->SetIntField(fdObj, fdField, fd);

    return env->NewObject(pfdClass, ctor, fdObj);
}
