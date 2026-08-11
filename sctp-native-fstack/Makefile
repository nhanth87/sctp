# Fallback when CMake is broken (AppImage CMAKE_ROOT / COLLECT_GCC).
CXX ?= /usr/bin/g++
CXXFLAGS ?= -std=c++20 -O2
INCLUDES = -Iinclude
BUILD = build

.PHONY: all clean
all: $(BUILD)/libsctp_fstack.so $(BUILD)/sctp-fstack

$(BUILD):
	mkdir -p $(BUILD)

$(BUILD)/libsctp_fstack.so: src/sctp_facade.cpp src/fstack_glue.cpp include/sctp_abi.h | $(BUILD)
	$(CXX) $(CXXFLAGS) -fPIC $(INCLUDES) -shared src/sctp_facade.cpp src/fstack_glue.cpp -o $@

$(BUILD)/sctp-fstack: src/sidecar_main.cpp src/sctp_facade.cpp src/fstack_glue.cpp include/sctp_abi.h include/sctp_ring.h | $(BUILD)
	$(CXX) $(CXXFLAGS) $(INCLUDES) src/sidecar_main.cpp src/sctp_facade.cpp src/fstack_glue.cpp -o $@

clean:
	rm -rf $(BUILD)
