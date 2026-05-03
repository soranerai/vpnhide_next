obj-m += vpnhide_kmod.o

ifeq ($(KERNELRELEASE),)

# KERNEL_SRC and CLANG_DIR come from the environment (set via .env + direnv).
# KERNEL_SRC and CLANG_DIR come from the environment (set via .env + direnv).
# They are only required for building the LKM (.ko).

ARCH           := arm64
CROSS_COMPILE  := aarch64-linux-gnu-
ifeq ($(CLANG_DIR),)
CC             := clang
LD             := ld.lld
AR             := llvm-ar
NM             := llvm-nm
OBJCOPY        := llvm-objcopy
OBJDUMP        := llvm-objdump
STRIP          := llvm-strip
else
CC             := $(CLANG_DIR)/clang
LD             := $(CLANG_DIR)/ld.lld
AR             := $(CLANG_DIR)/llvm-ar
NM             := $(CLANG_DIR)/llvm-nm
OBJCOPY        := $(CLANG_DIR)/llvm-objcopy
OBJDUMP        := $(CLANG_DIR)/llvm-objdump
STRIP          := $(CLANG_DIR)/llvm-strip
endif

MAKE_ARGS := -C $(KERNEL_SRC) M=$(CURDIR) \
	ARCH=$(ARCH) LLVM=1 LLVM_IAS=1 \
	CROSS_COMPILE=$(CROSS_COMPILE) \
	CC=$(CC) LD=$(LD) AR=$(AR) NM=$(NM) \
	OBJCOPY=$(OBJCOPY) OBJDUMP=$(OBJDUMP) STRIP=$(STRIP)

all:
ifndef KERNEL_SRC
	$(error KERNEL_SRC is not set for LKM build)
endif
	$(MAKE) $(MAKE_ARGS) modules

KP_DIR := $(CURDIR)/../kernels/KernelPatch

# KPM build flags
KPM_INCLUDE_DIRS := . include patch/include
KPM_INCLUDE_FLAGS := $(foreach dir,$(KPM_INCLUDE_DIRS),-I$(KP_DIR)/kernel/$(dir))
# KPM does NOT need kernel headers, it uses mocks and KernelPatch headers.

KPM_SRC ?= vpnhide_kpm.c

kpm54:
	$(CC) -target aarch64-linux-android \
		-O3 -fno-pic -fno-pie -fno-plt \
		-fvisibility=hidden -mno-outline-atomics \
		-mcmodel=small \
		-fno-stack-protector -fno-common -fno-builtin \
		-ffreestanding -fno-asynchronous-unwind-tables -fno-unwind-tables \
		-fno-stack-check -fno-stack-protector \
		-nostdinc \
		$(KPM_INCLUDE_FLAGS) \
		-I$(CURDIR)/generated \
		-D__KERNEL__ -DMODNAME=\"vpnhide\" \
		-DCONFIG_ARM64 \
		-r \
		-o vpnhide_5.4.kpm $(KPM_SRC)

kpm61:
	$(CC) -target aarch64-linux-android \
		-O3 -fno-pic -fno-pie -fno-plt \
		-fvisibility=hidden -mno-outline-atomics \
		-mcmodel=small \
		-fno-stack-protector -fno-common -fno-builtin \
		-ffreestanding -fno-asynchronous-unwind-tables -fno-unwind-tables \
		-fno-stack-check -fno-stack-protector \
		-nostdinc \
		$(KPM_INCLUDE_FLAGS) \
		-I$(CURDIR)/generated \
		-D__KERNEL__ -DMODNAME=\"vpnhide\" \
		-DCONFIG_ARM64 \
		-r \
		-o vpnhide_6.1.kpm $(KPM_SRC)

strip: all
ifndef CLANG_DIR
	$(error CLANG_DIR is not set)
endif
	$(STRIP) -d $(CURDIR)/vpnhide_kmod.ko

clean:
	$(MAKE) $(MAKE_ARGS) clean

endif
