CC = gcc
CFLAGS = -Wall -Wextra -pthread -Iinclude

BIN_DIR = bin
SRC_CLIENT = src/client/client.c
SRC_SERVER = src/server/server.c

TARGET_SERVER = $(BIN_DIR)/server
TARGET_CLIENT = $(BIN_DIR)/client

all: $(BIN_DIR) $(TARGET_SERVER) $(TARGET_CLIENT)

$(BIN_DIR):
	mkdir -p $(BIN_DIR)

$(TARGET_SERVER): $(SRC_SERVER)
	$(CC) $(CFLAGS) $^ -o $@

$(TARGET_CLIENT): $(SRC_CLIENT)
	$(CC) $(CFLAGS) $^ -o $@

clean:
	rm -rf $(BIN_DIR)/*

.PHONY: all clean