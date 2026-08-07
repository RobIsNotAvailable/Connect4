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

    if (connect(sock, (struct sockaddr *)&serv_addr, sizeof(serv_addr)) < 0)
    {
        perror("Connection failed\n");
        return -1;
    }

    Packet* pktptr = malloc(sizeof(Packet));
    pktptr->header.type = CMD_CREATE_GAME;
    pktptr->header.payload_size = 256;
    pktptr->data = 0;

    send(sock, pktptr, sizeof(Packet), 0);
    int add = 0;

    while ((recv(sock, pktptr, sizeof(Packet), 0)) > 0)
    {
        printf("[CLIENT] Received: %d\n", pktptr->data);
        printf("[CLIENT] How much to add?\n");
        scanf("%d", &add);
        pktptr->data += add;
        send(sock, pktptr, sizeof(Packet), 0);
    }

    return 0;
}