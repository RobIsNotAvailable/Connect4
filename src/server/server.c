#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <pthread.h>
#include "protocol.h"

void *client_handler(void *sock_id)
{
    int client_sock = *(int *)sock_id;

    printf("[SERVER] New client connected on socket %d\n", client_sock);

    Packet* pktptr = malloc(sizeof(Packet));
    int add = 0;

    int comm_status;

    while ((comm_status = recv(client_sock, pktptr, sizeof(Packet), 0)) > 0)
    {
        printf("[SERVER] Received: %d\n", pktptr->data);
        printf("[SERVER] How much to add?\n");
        scanf("%d", &add);
        pktptr->data += add;
        send(client_sock, pktptr, sizeof(Packet), 0);
    }
    if (comm_status == 0)
    {
        printf("[SERVER] Client on socket %d disconnected\n", client_sock);
    }
    else
    {
        perror("[SERVER] Error in receiving data from client\n");
    }

    close(client_sock);
    free(pktptr);
    pthread_exit(NULL);
}

int main()
{
    int server_sock, client_sock;
    struct sockaddr_in server_addr, client_addr;
    socklen_t addr_len = sizeof(client_addr);

    if ((server_sock = socket(AF_INET, SOCK_STREAM, 0)) < 0)
    {
        perror("Error in creating the server socket\n");
        return -1;
    }

    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = INADDR_ANY;
    server_addr.sin_port = htons(PORT);

    if (bind(server_sock, (struct sockaddr *)&server_addr, sizeof(server_addr)) < 0)
    {
        perror("Bind error\n");
        close(server_sock);
        return -1;
    }

    if (listen(server_sock, 10) < 0)
    {
        perror("Listen error");
        close(server_sock);
        return -1;
    }

    printf("=== SERVER STARTED ON PORT %d ===\n", PORT);

    while (1)
    {
        if ((client_sock = accept(server_sock, (struct sockaddr *)&client_addr, &addr_len)) < 0)
        {
            perror("Accept error");
            continue;
        }

        int *new_sock = malloc(sizeof(int));
        *new_sock = client_sock;
        pthread_t thread_id;

        if (pthread_create(&thread_id, NULL, client_handler, (void *)new_sock) < 0)
        {
            perror("Error in creating the thread");
            free(new_sock);
            close(client_sock);
        }
        else
        {
            pthread_detach(thread_id);
        }
    }

    close(server_sock);
    return 0;
}