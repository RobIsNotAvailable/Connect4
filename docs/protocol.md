# Protocollo client–server Forza 4

Questo documento definisce il formato dei messaggi scambiati tra client e
server. È il contratto tra le due metà del progetto: server e client (di
qualunque linguaggio) devono rispettarlo alla lettera.

## 1. Regole generali

### 1.1 Trasporto

- Socket TCP, porta `8080` (`PORT` in `include/protocol.h`).
- Una connessione per client, aperta all'avvio e tenuta per tutta la sessione.

### 1.2 Formato di un messaggio

- Ogni messaggio è **una riga di testo ASCII terminata da `\n`**.
- La riga è composta da **token separati da un singolo spazio**.
- Il **primo token** è il nome del comando, in maiuscolo (es. `JOIN_GAME`).
- I token successivi sono gli argomenti, in ordine fisso.
- Nessun token contiene spazi. Gli username sono assegnati dal server
  (`Player1`, `Player2`, …) e rispettano già questa regola.
- I testi scelti dal client (per ora il nome di una partita) sono un solo
  token: da **1 a 20 caratteri**, ciascuno ASCII stampabile diverso dallo
  spazio (da `!` a `~`, quindi lettere, cifre e simboli; niente accenti).
  Un nome con spazi viene visto come più token e dà `BAD_ARGS`; un nome
  troppo lungo o con caratteri non ammessi dà `INVALID_NAME`.
- Un eventuale `\r` prima del `\n` viene ignorato (così funzionano anche
  `telnet` e terminali che inviano `\r\n`).
- Lunghezza massima di una riga: **1024 byte**, `\n` compreso. Il server
  chiude la connessione a un client che invia una riga più lunga.

Esempio:

```
JOIN_GAME 12\n
```

### 1.3 Numeri

- Interi decimali, senza segno `+`, senza zeri iniziali.
- Gli id di client e di partita partono da `1`.

### 1.4 Direzione dei messaggi

I messaggi sono di due tipi:

- **Richieste** (client → server): il client chiede di fare qualcosa.
- **Eventi** (server → client): il server comunica un risultato o una
  notifica. Un evento può arrivare **in qualsiasi momento**, anche senza una
  richiesta precedente (es. `JOIN_NOTIFY`): il client deve quindi avere un
  lettore sempre attivo sul socket.

### 1.5 Errori

Quando una richiesta non può essere eseguita, il server risponde **solo** al
mittente con:

```
ERROR <comando> <codice>
```

- `<comando>` è il nome della richiesta che ha fallito, così il client sa a
  cosa si riferisce l'errore anche se nel frattempo ha inviato altro. Vale `-`
  se il comando non è stato riconosciuto.
- `<codice>` è uno dei codici della tabella seguente.

| Codice            | Significato                                                  |
|-------------------|--------------------------------------------------------------|
| `UNKNOWN_COMMAND` | Il primo token non è un comando valido                       |
| `BAD_ARGS`        | Numero di argomenti sbagliato, o argomento non numerico      |
| `INVALID_NAME`    | Nome troppo lungo, vuoto o con caratteri non ammessi (§1.2)  |
| `SERVER_FULL`     | Il server ha raggiunto il numero massimo di partite          |
| `TOO_MANY_GAMES`  | Il client ha già creato il numero massimo di partite (3)     |
| `NOT_FOUND`       | Nessuna partita con quell'id                                 |
| `NOT_WAITING`     | La partita non è in attesa di giocatori                      |
| `SELF_JOIN`       | Il client ha chiesto di unirsi alla propria partita          |
| `ALREADY_PENDING` | C'è già una richiesta (di accesso o di rivincita) in attesa  |
| `NOT_OWNER`       | Solo il creatore della partita può eseguire questa azione    |
| `NO_PENDING`      | Non c'è nessuna richiesta da accettare/rifiutare             |
| `ALREADY_PLAYING` | Il client sta già giocando un'altra partita                  |
| `NOT_PLAYER`      | Il client non è uno dei due giocatori di quella partita      |
| `NOT_PLAYING`     | La partita non è in corso                                    |
| `NOT_YOUR_TURN`   | Non è il turno del mittente                                  |
| `INVALID_COLUMN`  | Colonna fuori dall'intervallo 0–6                            |
| `COLUMN_FULL`     | La colonna è già piena                                       |
| `NOT_FINISHED`    | La partita non è ancora terminata                            |

## 2. Connessione

### `WELCOME` (server → client)

```
WELCOME <client_id> <username>
```

Primo messaggio inviato dal server subito dopo la connessione. Comunica al
client l'identità che gli è stata assegnata.

Esempio: `WELCOME 3 Player3`

Se il server è pieno, chiude la connessione senza inviare nulla.

## 3. Creazione e lista delle partite

### `CREATE_GAME` (client → server)

```
CREATE_GAME <name>
```

Crea una nuova partita in stato di attesa, con il mittente come creatore.
`<name>` è il nome che la partita mostra nella lista (regole in §1.2). I nomi
non sono univoci: due partite possono chiamarsi allo stesso modo, e si
distinguono per id e per creatore.
Un client può possedere al massimo **3** partite alla volta (`MAX_GAMES_PER_OWNER`
in `include/game_registry.h`), in qualunque stato si trovino.

Risposte possibili:
- `GAME_CREATED <game_id> <name>`
- `ERROR CREATE_GAME BAD_ARGS`
- `ERROR CREATE_GAME INVALID_NAME`
- `ERROR CREATE_GAME SERVER_FULL`
- `ERROR CREATE_GAME TOO_MANY_GAMES`

### `GAME_CREATED` (server → client)

```
GAME_CREATED <game_id> <name>
```

Il nome viene rimandato al creatore, così non deve ricordarsi cosa aveva
inviato per associarlo all'id. Il creatore non riceve `NEW_GAME` (§6).

Esempio: `GAME_CREATED 7 Sfida_1`

### `LIST_GAMES` (client → server)

```
LIST_GAMES
```

Chiede l'elenco delle partite in attesa di un secondo giocatore.

Risposta: `GAME_LIST`.

### `GAME_LIST` (server → client)

```
GAME_LIST <count> [<game_id> <name> <owner_username>]...
```

Dopo `<count>` seguono esattamente `<count>` terne id/nome/creatore. Contiene al
massimo 32 partite. Sono incluse solo le partite in attesa, perché sono le
uniche a cui ci si può unire.

Se le partite non stanno tutte nel limite di 1024 byte della riga (§1.2), il
server include solo quelle che ci stanno intere e `<count>` conta solo
quelle: la riga è sempre ben formata, ma l'elenco può essere più corto.

Esempi:

```
GAME_LIST 0
GAME_LIST 2 3 Sfida_1 Player1 7 Rivincita! Player4
```

## 4. Accesso a una partita

L'accesso è una stretta di mano in quattro messaggi tra il giocatore che vuole
entrare (*joiner*) e il creatore della partita (*owner*):

```
joiner                 server                  owner
  |  JOIN_GAME 7         |                       |
  |--------------------->|  JOIN_NOTIFY 7 Player2|
  |                      |---------------------->|
  |                      |  JOIN_RESPONSE 7 1    |
  |  JOIN_RESULT 7 1     |<----------------------|
  |<---------------------|                       |
```

### `JOIN_GAME` (client → server)

```
JOIN_GAME <game_id>
```

Chiede di unirsi alla partita indicata. Il joiner **non** riceve una risposta
immediata: l'esito arriva con `JOIN_RESULT` quando il creatore decide.

Errori possibili (inviati subito al joiner):
`BAD_ARGS`, `NOT_FOUND`, `NOT_WAITING`, `SELF_JOIN`, `ALREADY_PENDING`.

### `JOIN_NOTIFY` (server → owner)

```
JOIN_NOTIFY <game_id> <joiner_username>
```

Avvisa il creatore che qualcuno vuole unirsi alla sua partita.

Esempio: `JOIN_NOTIFY 7 Player2`

### `JOIN_RESPONSE` (owner → server)

```
JOIN_RESPONSE <game_id> <accepted>
```

`<accepted>` vale `1` per accettare, `0` per rifiutare.

Se accettata, la partita passa in corso. Se rifiutata, resta in attesa e può
ricevere nuove richieste.

Errori possibili: `BAD_ARGS`, `NOT_FOUND`, `NOT_OWNER`, `NO_PENDING`.

### `JOIN_RESULT` (server → joiner)

```
JOIN_RESULT <game_id> <accepted>
```

Comunica al joiner la decisione del creatore (`1` accettato, `0` rifiutato).

## 5. Partita in corso

### 5.1 Giocatori e turni

- Il creatore della partita è il **giocatore 1**, chi si unisce è il
  **giocatore 2**.
- Il giocatore 1 muove per primo.
- Un client può aver creato più partite, ma può **giocarne una sola alla
  volta**. Una richiesta che lo porterebbe in una seconda partita in corso
  riceve `ALREADY_PLAYING`:
  - `JOIN_GAME` inviato da chi sta già giocando;
  - `JOIN_RESPONSE ... 1` inviato da un creatore che sta già giocando altrove.

### 5.2 Formato della griglia

La griglia (6 righe × 7 colonne) viaggia come **un unico token di 42
caratteri**, riga per riga **dall'alto verso il basso**, e in ogni riga da
sinistra a destra:

| Carattere | Cella                  |
|-----------|------------------------|
| `.`       | vuota                  |
| `1`       | disco del giocatore 1  |
| `2`       | disco del giocatore 2  |

Il carattere in posizione `riga * 7 + colonna` è la cella (riga, colonna), con
la riga 0 in alto e la colonna 0 a sinistra.

Esempio: il giocatore 1 ha giocato nella colonna 3, il giocatore 2 nella
colonna 4:

```
.......
.......
.......
.......
.......
...12..
```

diventa il token

```
......................................12..
```

(35 punti, poi `...12..`).

### `GAME_START` (server → entrambi i giocatori)

```
GAME_START <game_id> <your_player> <opponent_username>
```

Inviato ai due giocatori quando la partita inizia (join accettato o rivincita
accettata). `<your_player>` vale `1` o `2` e dice al destinatario quale
giocatore è. Subito dopo segue un `GAME_STATE` con la griglia vuota.

Esempio (al joiner): `GAME_START 7 2 Player1`

### `GAME_STATE` (server → entrambi i giocatori)

```
GAME_STATE <game_id> <turn> <board>
```

Stato completo della partita, inviato a entrambi dopo l'inizio e dopo ogni
mossa valida.

- `<turn>` è `1` o `2`: il giocatore che deve muovere. Vale `0` se la partita
  è appena terminata.
- `<board>` è la griglia nel formato della sezione 5.2.

Il client confronta `<turn>` con il `<your_player>` ricevuto in `GAME_START`
per sapere se tocca a lui.

### `MOVE` (client → server)

```
MOVE <game_id> <column>
```

Lascia cadere un disco nella colonna indicata (0–6). Il disco finisce nella
cella libera più in basso.

Se la mossa è valida, il server risponde a entrambi con `GAME_STATE` e, se la
partita è finita, con `GAME_OVER`.

Errori possibili: `BAD_ARGS`, `NOT_FOUND`, `NOT_PLAYER`, `NOT_PLAYING`,
`NOT_YOUR_TURN`, `INVALID_COLUMN`, `COLUMN_FULL`.

### `GAME_OVER` (server → ciascun giocatore)

```
GAME_OVER <game_id> <result>
```

Esito **personalizzato** per ciascun giocatore. `<result>` vale:

| Valore | Significato       |
|--------|-------------------|
| `WIN`  | hai vinto         |
| `LOSE` | hai perso         |
| `DRAW` | pareggio (griglia piena) |

Il vincitore riceve `WIN` e l'avversario `LOSE`; in caso di pareggio entrambi
ricevono `DRAW`. È sempre preceduto dal `GAME_STATE` finale, così il client
può mostrare la mossa decisiva.

Da questo momento la partita è **terminata** e i due giocatori sono liberi di
giocare altre partite.

## 6. Notifiche agli altri client

Tutti i client connessi vengono informati dei cambi di stato delle partite
senza doverli chiedere con `LIST_GAMES`. Queste notifiche **non** vengono
inviate ai client coinvolti nella partita (creatore e, se c'è, secondo
giocatore), che ricevono già i messaggi dettagliati delle sezioni precedenti.

### `NEW_GAME` (server → altri client)

```
NEW_GAME <game_id> <name> <owner_username>
```

Una nuova partita è in attesa di un secondo giocatore. Porta tutto ciò che
serve per mostrarla nella lista (id, nome, creatore), senza dover richiedere
`LIST_GAMES`.

Esempio: `NEW_GAME 7 Sfida_1 Player1`

### `GAME_IN_PROGRESS` (server → altri client)

```
GAME_IN_PROGRESS <game_id>
```

La partita è in corso e non è più possibile unirsi. Inviato anche quando
riparte con una rivincita.

### `GAME_CLOSED` (server → altri client)

```
GAME_CLOSED <game_id>
```

La partita si è conclusa oppure è stata eliminata (es. il creatore si è
disconnesso mentre era in attesa). Il client la toglie dalla lista.

## 7. Rivincita e uscita

Dopo `GAME_OVER` i due giocatori possono giocare di nuovo nella stessa
partita. La rivincita segue lo stesso schema dell'accesso: una richiesta,
una notifica, una risposta, un esito.

```
giocatore A            server                 giocatore B
  |  REMATCH_REQUEST 7   |                       |
  |--------------------->|  REMATCH_NOTIFY 7     |
  |                      |---------------------->|
  |                      |  REMATCH_RESPONSE 7 1 |
  |  REMATCH_RESULT 7 1  |<----------------------|
  |<---------------------|                       |
  |  GAME_START ...      |  GAME_START ...       |
  |  GAME_STATE ...      |  GAME_STATE ...       |
```

### `REMATCH_REQUEST` (client → server)

```
REMATCH_REQUEST <game_id>
```

Proposta di rivincita, inviabile da uno dei due giocatori a partita
terminata.

Errori possibili: `BAD_ARGS`, `NOT_FOUND`, `NOT_PLAYER`, `NOT_FINISHED`,
`ALREADY_PENDING` (anche quando l'avversario ha già proposto la rivincita: in
quel caso basta rispondere al suo `REMATCH_NOTIFY`), `ALREADY_PLAYING`.

### `REMATCH_NOTIFY` (server → avversario)

```
REMATCH_NOTIFY <game_id>
```

### `REMATCH_RESPONSE` (client → server)

```
REMATCH_RESPONSE <game_id> <accepted>
```

`<accepted>` vale `1` per accettare, `0` per rifiutare.

- **Accettata:** la griglia viene svuotata, la partita torna in corso, il
  giocatore 1 muove per primo. Entrambi ricevono `GAME_START` e `GAME_STATE`,
  gli altri client `GAME_IN_PROGRESS`.
- **Rifiutata:** la partita viene eliminata.

Errori possibili: `BAD_ARGS`, `NOT_FOUND`, `NOT_PLAYER`, `NO_PENDING`,
`ALREADY_PLAYING`.

### `REMATCH_RESULT` (server → chi ha proposto)

```
REMATCH_RESULT <game_id> <accepted>
```

### `LEAVE_GAME` (client → server)

```
LEAVE_GAME <game_id>
```

Uno dei due giocatori abbandona una partita **terminata** senza giocare la
rivincita. La partita viene eliminata e l'avversario riceve `OPPONENT_LEFT`.

Errori possibili: `BAD_ARGS`, `NOT_FOUND`, `NOT_PLAYER`, `NOT_FINISHED`.

## 8. Disconnessioni

Quando un client si disconnette, il server applica queste regole a ogni
partita in cui era coinvolto:

| Situazione                                    | Cosa succede                                                                 |
|-----------------------------------------------|------------------------------------------------------------------------------|
| Creatore di una partita in attesa             | Partita eliminata; tutti gli altri client ricevono `GAME_CLOSED`, compreso un eventuale joiner in attesa di risposta, che deve considerare la richiesta chiusa |
| Joiner con una richiesta di accesso in attesa | La richiesta viene annullata; il creatore riceve `JOIN_CANCELLED`            |
| Giocatore di una partita in corso o terminata | Partita eliminata; l'avversario riceve `OPPONENT_LEFT`, gli altri client `GAME_CLOSED` (solo se era in corso) |

In caso di disconnessione a metà partita non viene assegnata la vittoria a
nessuno: la partita viene semplicemente chiusa.

### `JOIN_CANCELLED` (server → owner)

```
JOIN_CANCELLED <game_id>
```

Il joiner si è disconnesso prima che il creatore rispondesse. La partita resta
in attesa e può ricevere nuove richieste.

### `OPPONENT_LEFT` (server → giocatore rimasto)

```
OPPONENT_LEFT <game_id>
```

L'avversario si è disconnesso o ha inviato `LEAVE_GAME`. La partita non esiste
più.

## 9. Esempio di sessione con netcat

Con il server avviato, due terminali con `nc localhost 8080`. Le righe con
`>` sono scritte a mano, le altre arrivano dal server.

Terminale A:

```
WELCOME 1 Player1
> CREATE_GAME Sfida_1
GAME_CREATED 1 Sfida_1
JOIN_NOTIFY 1 Player2
> JOIN_RESPONSE 1 1
```

Terminale B:

```
WELCOME 2 Player2
> LIST_GAMES
GAME_LIST 1 1 Sfida_1 Player1
> JOIN_GAME 1
JOIN_RESULT 1 1
```
