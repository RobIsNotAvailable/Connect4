#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include "protocol.h"


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
    pktptr->header.type = CMD_CREATE_GAME;
    pktptr->header.payload_size = 0; // CMD_CREATE_GAME carries no payload

    send(sock, pktptr, sizeof(Packet), 0);

    // Connectivity test only: the server currently just echoes the
    // packet back. Real CMD_CREATE_GAME handling (assigning a game id,
    // etc.) will be added on the server side in Phase 2.
    if (recv(sock, pktptr, sizeof(Packet), 0) > 0)
    {
        printf("[CLIENT] Server echoed command type: %d\n", pktptr->header.type);
    }

    free(pktptr);
    close(sock);
    return 0;
}