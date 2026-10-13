LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

# The adapter's native half, when this build has one: BTstack and the HCI transport that points it at
# the USB adapter. Its makefile lives with the rest of the adapter, outside this repository, and
# ADAPTER_HCI_JNI is where the build says that is. Above the hop fragment because both of them set
# LOCAL_PATH to their own directory; neither depends on the other's value, but the last one wins.
ifdef ADAPTER_HCI_JNI
include $(ADAPTER_HCI_JNI)/Android.mk
endif

# The car hop's native half, when this build has one. Its makefile lives with the rest of the hop,
# outside this repository, and VENDOR_HOP_JNI is where the build says that is.
ifdef VENDOR_HOP_JNI
include $(VENDOR_HOP_JNI)/Android.mk
endif
