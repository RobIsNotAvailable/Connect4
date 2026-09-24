CC = gcc
CFLAGS = -Wall -Wextra -pthread -Iinclude

BIN_DIR = bin
SRC_SERVER = src/server/server.c src/server/client_registry.c src/server/game_registry.c src/server/board.c src/server/net.c
HEADERS = include/protocol.h include/net.h include/client_registry.h include/game_registry.h include/board.h

TARGET_SERVER = $(BIN_DIR)/server

all: $(BIN_DIR) $(TARGET_SERVER)

$(BIN_DIR):
	mkdir -p $(BIN_DIR)

$(TARGET_SERVER): $(SRC_SERVER) $(HEADERS)
	$(CC) $(CFLAGS) $(SRC_SERVER) -o $@


clean:
	rm -rf $(BIN_DIR)/*

# Runs every end-to-end test in tests/ against the real server (each test
# file starts its own copy). All of them run even if one fails.
test: all
	@status=0; for t in tests/test_*.py; do python3 $$t || status=1; done; exit $$status

.PHONY: all clean test
