#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include "protocol.h"


// Sends CMD_CREATE_GAME and prints the id assigned by the server.
void create_game(int sock)
{
    Packet req;
    req.header.type = CMD_CREATE_GAME;
    req.header.payload_size = 0;
    send(sock, &req, sizeof(Packet), 0);

    Packet resp;
    if (recv(sock, &resp, sizeof(Packet), 0) <= 0 || resp.header.type != CMD_GAME_CREATED)
    {
        fprintf(stderr, "[CLIENT] Did not receive a valid CMD_GAME_CREATED reply\n");
        return;
    }

    if (resp.payload.game_created.game_id == -1)
    {
        printf("[CLIENT] Server could not create the game (registry full)\n");
    }
    else
    {
        printf("[CLIENT] Game created, id=%d\n", resp.payload.game_created.game_id);
    }
}

// Sends CMD_LIST_GAMES and prints the WAITING games returned by the server.
void list_games(int sock)
{
    Packet req;
    req.header.type = CMD_LIST_GAMES;
    req.header.payload_size = 0;
    send(sock, &req, sizeof(Packet), 0);

    Packet resp;
    if (recv(sock, &resp, sizeof(Packet), 0) <= 0 || resp.header.type != CMD_GAME_LIST)
    {
        fprintf(stderr, "[CLIENT] Did not receive a valid CMD_GAME_LIST reply\n");
        return;
    }

    GameList *list = &resp.payload.game_list;
    if (list->count == 0)
    {
        printf("[CLIENT] No games waiting for players\n");
        return;
    }

    printf("[CLIENT] Games waiting for players:\n");
    for (int i = 0; i < list->count; i++)
    {
        printf("  id=%d owner=%s\n", list->games[i].game_id, list->games[i].owner_username);
    }
}

int main()
{
    int sock = 0;
    struct sockaddr_in serv_addr;

    if ((sock = socket(AF_INET, SOCK_STREAM, 0)) < 0)
    {
        perror("Error in creating the client socket\n");
        return -1;
    }

    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons(PORT);

    // Explicitly set the server IP. Without this, sin_addr is left
    // uninitialized; it happened to work on Linux because a fresh stack
    // is usually zeroed and connect() treats 0.0.0.0 as localhost, but
    // that behavior is not guaranteed (e.g. across containers in Docker).
    if (inet_pton(AF_INET, "127.0.0.1", &serv_addr.sin_addr) <= 0)
    {
        perror("Invalid server address\n");
        return -1;
    }

    if (connect(sock, (struct sockaddr *)&serv_addr, sizeof(serv_addr)) < 0)
    {
        perror("Connection failed\n");
        return -1;
    }

    Packet* pktptr = malloc(sizeof(Packet));

    // The server assigns an id/username right after accept(); this is the
    // first message we expect to receive.
    if (recv(sock, pktptr, sizeof(Packet), 0) > 0 && pktptr->header.type == CMD_WELCOME)
    {
        printf("[CLIENT] Connected as %s (id=%d)\n",
               pktptr->payload.welcome.username, pktptr->payload.welcome.client_id);
    }
    else
    {
        fprintf(stderr, "[CLIENT] Did not receive a valid welcome message from the server\n");
        free(pktptr);
        close(sock);
        return -1;
    }

    free(pktptr);

    // Minimal menu to exercise CMD_CREATE_GAME / CMD_LIST_GAMES for now.
    // Will be replaced by the full textual menu + grid rendering in Phase 7.
    int choice = 0;
    do
    {
        printf("\n1) Create game\n2) List games\n0) Exit\n> ");
        if (scanf("%d", &choice) != 1)
        {
            break;
        }

        switch (choice)
        {
            case 1:
                create_game(sock);
                break;
            case 2:
                list_games(sock);
                break;
        }
    } while (choice != 0);

    close(sock);
    return 0;
}