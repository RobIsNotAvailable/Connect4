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
    close(sock);
    return 0;
}