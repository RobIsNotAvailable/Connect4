CC = gcc
CFLAGS = -Wall -Wextra -pthread -Iinclude

BIN_DIR = bin
SRC_SERVER = $(wildcard src/*.c)
HEADERS = $(wildcard include/*.h)

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
