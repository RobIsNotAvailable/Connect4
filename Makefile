CC = gcc
CFLAGS = -Wall -Wextra -pthread -Iinclude

BIN_DIR = bin
SRC_CLIENT = src/client/client.c
SRC_SERVER = src/server/server.c src/server/client_registry.c src/server/game_registry.c src/server/board.c
SRC_COMMON = src/common/net.c
HEADERS = include/protocol.h include/net.h include/client_registry.h include/game_registry.h include/board.h

TARGET_SERVER = $(BIN_DIR)/server
TARGET_CLIENT = $(BIN_DIR)/client

all: $(BIN_DIR) $(TARGET_SERVER) $(TARGET_CLIENT)

$(BIN_DIR):
	mkdir -p $(BIN_DIR)

$(TARGET_SERVER): $(SRC_SERVER) $(SRC_COMMON) $(HEADERS)
	$(CC) $(CFLAGS) $(SRC_SERVER) $(SRC_COMMON) -o $@

$(TARGET_CLIENT): $(SRC_CLIENT) $(SRC_COMMON) $(HEADERS)
	$(CC) $(CFLAGS) $(SRC_CLIENT) $(SRC_COMMON) -o $@

clean:
	rm -rf $(BIN_DIR)/*

# Runs every end-to-end test in tests/ against the real server (each test
# file starts its own copy). All of them run even if one fails.
test: all
	@status=0; for t in tests/test_*.py; do python3 $$t || status=1; done; exit $$status

.PHONY: all clean test
