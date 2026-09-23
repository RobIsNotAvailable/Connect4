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
- Nessun token contiene spazi. Anche gli username la rispettano: li sceglie
  il client (§2) e sono un solo token.
- I testi scelti dal client (il nome di una partita e lo username) sono un solo
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
| `NO_USERNAME`     | Il client non ha ancora scelto lo username (§2)              |
| `ALREADY_NAMED`   | Il client ha già scelto lo username e non può cambiarlo      |
| `USERNAME_TAKEN`  | Un altro client connesso usa già quello username             |
| `NOT_FOUND`       | Nessuna partita con quell'id                                 |
| `NOT_WAITING`     | La partita non è in attesa di giocatori                      |
| `SELF_JOIN`       | Il client ha chiesto di unirsi alla propria partita          |
| `ALREADY_PENDING` | C'è già una richiesta di accesso in attesa, o il mittente ha già chiesto la rivincita |
| `NOT_OWNER`       | Solo il creatore della partita può eseguire questa azione    |
| `NO_PENDING`      | Non c'è nessuna richiesta da accettare/rifiutare             |
| `NOT_PLAYER`      | Il client non è uno dei due giocatori di quella partita      |
| `NOT_PLAYING`     | La partita non è in corso                                    |
| `NOT_ACTIVE`      | La partita non è la partita attiva del client (§5.3)         |
| `TOO_MANY_MATCHES`| Il client gioca già il numero massimo di partite (5, §5.1)   |
| `JOINER_FULL`     | Chi chiedeva di entrare ha raggiunto quel numero nel frattempo |
| `NOT_YOUR_TURN`   | Non è il turno del mittente                                  |
| `INVALID_COLUMN`  | Colonna fuori dall'intervallo 0–6                            |
| `COLUMN_FULL`     | La colonna è già piena                                       |
| `NOT_FINISHED`    | La partita non è ancora terminata                            |

## 2. Connessione

### `WELCOME` (server → client)

```
WELCOME <client_id>
```

Primo messaggio inviato dal server subito dopo la connessione. Comunica al
client l'id che gli è stato assegnato. Il client non ha ancora uno username:
deve sceglierlo con `SET_USERNAME`.

Esempio: `WELCOME 3`

Se il server è pieno, chiude la connessione senza inviare nulla.

### `SET_USERNAME` (client → server)

```
SET_USERNAME <username>
```

Sceglie lo username del client. Si fa **una sola volta**, subito dopo la
connessione, e poi non si può più cambiare: finché non è stato scelto, il
server rifiuta ogni altro comando con `ERROR <comando> NO_USERNAME`. Così ogni
nome che compare in una partita o in una notifica è già definitivo.

- `<username>` rispetta le regole dei nomi di §1.2 (da 1 a 20 caratteri).
- Gli username sono **univoci** tra i client connessi, senza distinguere
  maiuscole e minuscole: `Anna` e `anna` sono lo stesso nome. Uno username
  torna libero quando il suo client si disconnette.

Risposte possibili:
- `USERNAME_SET <username>`
- `ERROR SET_USERNAME BAD_ARGS`
- `ERROR SET_USERNAME INVALID_NAME`
- `ERROR SET_USERNAME USERNAME_TAKEN` (il client può riprovare con un altro nome)
- `ERROR SET_USERNAME ALREADY_NAMED`

### `USERNAME_SET` (server → client)

```
USERNAME_SET <username>
```

Conferma lo username scelto. Da questo momento il client può usare tutti gli
altri comandi.

Esempio: `USERNAME_SET Anna`

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

### `LIST_MY_GAMES` (client → server)

```
LIST_MY_GAMES
```

Chiede l'elenco delle partite di cui il mittente è il creatore (*owner*), in
qualunque stato si trovino. Serve al creatore per ritrovare le proprie stanze
senza doverle cercare in `GAME_LIST`, che mostra solo quelle in attesa.

Risposta: `MY_GAME_LIST`.

### `MY_GAME_LIST` (server → client)

```
MY_GAME_LIST <count> [<game_id> <name> <state>]...
```

Dopo `<count>` seguono esattamente `<count>` terne id/nome/stato. `<state>` è
uno tra:

- `WAITING`: la partita aspetta un secondo giocatore;
- `PLAYING`: si sta giocando;
- `FINISHED`: la partita è finita e la stanza esiste ancora.

Sono elencate solo le partite di cui il mittente è **owner**, non quelle in cui
gioca da secondo giocatore. Un utente ne ha al massimo 3 (`TOO_MANY_GAMES`,
§3), quindi `<count>` va da 0 a 3 e la riga non viene mai troncata.

Esempi:

```
MY_GAME_LIST 0
MY_GAME_LIST 2 3 Sfida_1 PLAYING 7 Rivincita! WAITING
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
`BAD_ARGS`, `NOT_FOUND`, `NOT_WAITING`, `SELF_JOIN`, `ALREADY_PENDING`,
`TOO_MANY_MATCHES` (il joiner gioca già il numero massimo di partite, §5.1).

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

Errori possibili: `BAD_ARGS`, `NOT_FOUND`, `NOT_OWNER`, `NO_PENDING`,
`TOO_MANY_MATCHES` (il creatore gioca già il numero massimo di partite: la
richiesta resta in attesa, può rifiutarla o accettarla dopo aver lasciato una
partita), `JOINER_FULL` (chi chiedeva ha raggiunto quel numero dopo aver
chiesto: la richiesta viene annullata e lui riceve `JOIN_RESULT <game_id> 0`).

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
- Un client può trovarsi in più partite **contemporaneamente**: crearne fino
  a 3, e farsi accettare in altre. Ciascuna può essere in corso, e il server
  non pone limiti al numero di partite in corso di un client: chiedere di
  entrare, accettare una richiesta e votare la rivincita valgono anche a chi
  sta già giocando altrove. Attivamente però se ne gioca una sola alla volta:
  la partita attiva (§5.3).
- Le partite in corso di un client sono al massimo **5** (`MAX_GAMES_PER_PLAYER`):
  contano quelle di cui è un giocatore e che hanno un avversario, in corso o
  terminate (una partita terminata conta finché il giocatore non la lascia).
  Oltre il limite, chiedere di entrare dà `TOO_MANY_MATCHES`; accettare una
  richiesta lo dà al creatore, e `JOINER_FULL` se è il joiner ad aver
  raggiunto il limite nel frattempo. La rivincita non cambia il numero.
- Le partite di uno stesso client sono indipendenti. Ogni messaggio che le
  riguarda (`MOVE`, `GAME_STATE`, `GAME_OVER`...) porta l'id della sua partita
  e arriva solo ai due giocatori di quella partita, quindi il client sa a
  quale appartiene.

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

### 5.3 Partita attiva

Un client può essere in più partite, ma ne **gioca attivamente una sola alla
volta**: la sua *partita attiva*. `MOVE` è accettata solo in quella; le altre
restano sospese, con griglia e turno com'erano, finché il client non le rende
attive.

- Alla connessione nessuna partita è attiva.
- Quando una partita inizia (un accesso accettato o una rivincita) diventa la
  partita attiva di ciascun giocatore che non ne ha una in corso, cioè che non
  ne ha nessuna o la cui partita attiva è terminata. Chi sta giocando un'altra
  partita la mantiene. Un client che gioca una partita per volta non deve
  quindi mandare niente: la sua partita è già attiva.
- Il client cambia partita attiva con `SET_ACTIVE_GAME`. Una partita terminata
  resta attiva finché il giocatore non ne sceglie un'altra o non esce.
- Quando un giocatore esce da una partita (`LEAVE_GAME` o disconnessione),
  quella non è più la partita attiva né sua né di chi resta: per chi resta la
  stanza è tornata in attesa (§8).

### `SET_ACTIVE_GAME` (client → server)

```
SET_ACTIVE_GAME <game_id>
```

Rende attiva la partita indicata. `<game_id>` deve essere una partita di cui il
mittente è il creatore o il secondo giocatore (non una in cui ha solo una
richiesta in attesa) e che non sia in attesa di un avversario; una partita
terminata va bene. `0` toglie la partita attiva.

Se va a buon fine il server **non risponde**: lo mostra la `MOVE` successiva.

Errori possibili: `BAD_ARGS`, `NOT_FOUND`, `NOT_PLAYER`, `NOT_PLAYING` (la
partita è in attesa di un avversario).

### `OPPONENT_STATUS` (server → giocatore)

```
OPPONENT_STATUS <game_id> <status>
```

Dice se l'avversario di una partita la sta guardando. `<status>` è:

- `HERE`: la partita attiva dell'avversario è questa;
- `AWAY`: la sua partita attiva è un'altra oppure non ne ha nessuna: è nella
  lobby o sta giocando altrove, in ogni caso in questa non muoverà finché non
  torna.

Il client non deve chiedere niente: fino a nuovo avviso lo stato di una
partita è `HERE`, e il server manda `OPPONENT_STATUS` solo quando cambia.
`GAME_START` riporta lo stato a `HERE`, anche per una rivincita: se
l'avversario sta già giocando un'altra partita, l'`AWAY` arriva subito dopo il
`GAME_STATE` iniziale. Chi gioca una partita per volta non riceve mai questo
messaggio, a meno che l'avversario non lasci la partita attiva (§5.3).

Lo stato dipende solo da `SET_ACTIVE_GAME`, dall'avvio di una partita e
dall'uscita da una partita (§5.3): una partita finita ancora aperta conta come
partita attiva finché il giocatore non ne sceglie un'altra o esce.

### `LIST_MY_MATCHES` (client → server)

```
LIST_MY_MATCHES
```

Chiede l'elenco delle partite che il mittente sta giocando (§5.1), come
creatore o come secondo giocatore, in corso o terminate. Le stanze in attesa
di un avversario non ci sono: si trovano con `LIST_MY_GAMES` (§3), che elenca
le stanze possedute in qualunque stato ma senza avversario.

Risposta: `MY_MATCH_LIST`.

### `MY_MATCH_LIST` (server → client)

```
MY_MATCH_LIST <count> [<game_id> <name> <opponent> <my_player> <state> <turn> <status>]...
```

Dopo `<count>` seguono esattamente `<count>` gruppi di 7 token, in ordine di
id crescente:

- `<name>`: il nome della stanza; `<opponent>`: lo username dell'avversario;
- `<my_player>`: `1` o `2`, il numero del mittente in quella partita;
- `<state>`: `PLAYING` oppure `FINISHED`;
- `<turn>`: chi deve muovere (`1` o `2`), come in `GAME_STATE`; `0` se la
  partita è terminata;
- `<status>`: `HERE` o `AWAY` per l'avversario, come in
  `OPPONENT_STATUS`.

`<count>` va da 0 a 5, quindi la riga non viene mai troncata.

Esempi:

```
MY_MATCH_LIST 0
MY_MATCH_LIST 2 3 Sfida_1 Bruno 1 PLAYING 2 HERE 7 Rivincita! Carla 2 FINISHED 0 AWAY
```

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
`NOT_ACTIVE` (non è la partita attiva del mittente, §5.3), `NOT_YOUR_TURN`,
`INVALID_COLUMN`, `COLUMN_FULL`. Se più di una condizione è vera, l'errore è il
primo di questo elenco.

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

Da questo momento la partita è **terminata**: i due giocatori possono chiedere
la rivincita o lasciare la partita (§7), e sono liberi di giocare altre partite.

## 6. Notifiche agli altri client

Tutti i client connessi vengono informati dei cambi di stato delle partite
senza doverli chiedere con `LIST_GAMES`. Queste notifiche **non** vengono
inviate ai client coinvolti nella partita (creatore e, se c'è, secondo
giocatore), che ricevono già i messaggi dettagliati delle sezioni precedenti.

### `NEW_GAME` (server → altri client)

```
NEW_GAME <game_id> <name> <owner_username>
```

Una partita è in attesa di un secondo giocatore: appena creata, oppure
tornata in attesa perché uno dei due giocatori è uscito (§8). Porta tutto ciò
che serve per mostrarla nella lista (id, nome, creatore), senza dover
richiedere `LIST_GAMES`. Nel secondo caso il creatore può essere diverso da
quello di prima.

Esempio: `NEW_GAME 7 Sfida_1 Player1`

### `GAME_IN_PROGRESS` (server → altri client)

```
GAME_IN_PROGRESS <game_id>
```

La partita è in corso e non è più possibile unirsi. Non viene inviato quando
riparte con una rivincita: gli altri client hanno già ricevuto `GAME_CLOSED`
alla fine della partita precedente, quindi non ce l'hanno in lista.

### `GAME_CLOSED` (server → altri client)

```
GAME_CLOSED <game_id>
```

La partita esce dalla lista: si è conclusa, oppure è stata eliminata (es. il
creatore si è disconnesso mentre era in attesa, o se n'è andato l'ultimo
giocatore rimasto, §8). Il client la toglie dalla lista. Una partita conclusa
può tornare in lista con `NEW_GAME` se uno dei due giocatori esce.

## 7. Rivincita e uscita

Dopo `GAME_OVER` ognuno dei due giocatori sceglie tra due cose: chiedere la
rivincita (`REMATCH`) o lasciare la partita (`LEAVE_GAME`). Un client può
mostrare a entrambi una finestra con questi due pulsanti.

La rivincita è un voto e non ha una parte che propone e una che risponde: si
gioca di nuovo solo se la vogliono **entrambi**, in qualsiasi ordine.

```
giocatore A            server                 giocatore B
  |  REMATCH 7           |                       |
  |--------------------->|  REMATCH_NOTIFY 7     |
  |                      |---------------------->|
  |                      |           REMATCH 7   |
  |  GAME_START ...      |<----------------------|
  |  GAME_STATE ...      |  GAME_START ...       |
  |<---------------------|  GAME_STATE ...       |
  |                      |---------------------->|
```

### `REMATCH` (client → server)

```
REMATCH <game_id>
```

Il mittente vuole rigiocare. Vale per entrambi i giocatori, a partita
terminata.

- Se l'avversario non ha ancora votato, il server non risponde al mittente e
  manda all'avversario `REMATCH_NOTIFY`. Il voto non si ritira: per
  rinunciare si esce con `LEAVE_GAME`.
- Se l'avversario aveva già votato, la partita riparte: la griglia viene
  svuotata, la partita torna in corso e il giocatore 1 muove per primo.
  Entrambi ricevono `GAME_START` e `GAME_STATE`, come quando un accesso viene
  accettato. Gli altri client non ricevono niente: sono già stati avvisati con
  `GAME_CLOSED` quando la partita è finita (§6).

Chi ha votato riceve quindi una risposta solo quando la partita riparte o c'è
un errore.

Errori possibili: `BAD_ARGS`, `NOT_FOUND`, `NOT_PLAYER`, `NOT_FINISHED`,
`ALREADY_PENDING` (il mittente ha già votato).

Se un giocatore esce (`LEAVE_GAME` o disconnessione) la partita torna in
attesa (§8), i voti non valgono più e `REMATCH` dà `NOT_FINISHED`.

### `REMATCH_NOTIFY` (server → avversario)

```
REMATCH_NOTIFY <game_id>
```

L'avversario ha chiesto la rivincita. Il destinatario può accettare con
`REMATCH` o rifiutare con `LEAVE_GAME`.

Esempio: `REMATCH_NOTIFY 7`

### `LEAVE_GAME` (client → server)

```
LEAVE_GAME <game_id>
```

Uno dei due giocatori lascia la partita, in qualunque stato si trovi: in
attesa (può farlo solo il creatore, da solo), in corso o terminata. La partita
non viene eliminata: valgono le stesse regole di una disconnessione (§8).

- Il **secondo giocatore** esce: la partita torna in attesa, con la griglia
  vuota. Il creatore riceve `OPPONENT_LEFT`.
- Il **creatore**, con un secondo giocatore, esce: il secondo giocatore
  diventa il creatore e la partita torna in attesa. Lui riceve
  `OPPONENT_LEFT`.
- Il **creatore da solo** esce: la partita viene eliminata. È il modo di
  eliminare una partita che si è creata, e libera un posto tra le 3 consentite.

Uscire a metà partita non assegna la vittoria a nessuno.

Il mittente riceve `GAME_LEFT`. Da quel momento è un client come gli altri
rispetto a quella partita, e ne riceve le notifiche di §6: per esempio
`NEW_GAME` se la partita torna in lista, ma non `GAME_CLOSED` se è stata
eliminata (gli basta `GAME_LEFT`). Un client può uscire da una partita e
subito dopo unirsi a un'altra, o alla stessa (§5.1: gioca una partita alla
volta).

Errori possibili: `BAD_ARGS`, `NOT_FOUND`, `NOT_PLAYER` (anche per chi ha solo
una richiesta di accesso in attesa, che non è ancora un giocatore).

### `GAME_LEFT` (server → client)

```
GAME_LEFT <game_id>
```

Conferma a chi ha inviato `LEAVE_GAME` che ha lasciato la partita. Arriva prima
delle eventuali notifiche di §6 su quella stessa partita.

Esempio: `GAME_LEFT 7`

## 8. Uscite e disconnessioni

Una partita (la stanza) sopravvive ai giocatori che ci sono dentro: quando ne
esce uno, resta l'altro. Quando un client si disconnette, il server applica
queste regole a ogni partita in cui era coinvolto. Sono le stesse di
`LEAVE_GAME` (§7), che le applica a una sola partita e lascia il client
connesso:

| Situazione                                    | Cosa succede                                                                 |
|-----------------------------------------------|------------------------------------------------------------------------------|
| Creatore di una partita in attesa             | Partita eliminata; tutti gli altri client ricevono `GAME_CLOSED`, compreso un eventuale joiner in attesa di risposta, che deve considerare la richiesta chiusa |
| Joiner con una richiesta di accesso in attesa | La richiesta viene annullata; il creatore riceve `JOIN_CANCELLED`            |
| Secondo giocatore di una partita in corso o terminata | La partita resta e torna in attesa, con la griglia vuota. Il creatore riceve `OPPONENT_LEFT`, gli altri client `NEW_GAME` |
| Creatore di una partita in corso o terminata  | Il secondo giocatore diventa il creatore e la partita torna in attesa, con la griglia vuota. Lui riceve `OPPONENT_LEFT`, gli altri client `NEW_GAME` con il suo username |

Un client possiede al massimo 3 partite (§3): se il secondo giocatore che
dovrebbe subentrare come creatore ne possiede già 3, non può farlo e la partita
viene eliminata. In quel caso lui, come tutti gli altri client, riceve
`GAME_CLOSED` (e non `OPPONENT_LEFT`).

Una partita sparisce solo quando non resta nessuno che possa possederla: se
escono entrambi i giocatori, uno dopo l'altro, l'ultimo a restare è un creatore
solo in una partita in attesa e la partita viene eliminata (`GAME_CLOSED`).

In caso di disconnessione a metà partita non viene assegnata la vittoria a
nessuno: la partita ricomincia da capo quando arriva un nuovo giocatore.

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

L'avversario si è disconnesso o ha inviato `LEAVE_GAME`. La partita esiste
ancora: adesso è in attesa di un nuovo giocatore, con la griglia vuota, e il
destinatario ne è il creatore (anche se prima era il secondo giocatore).

Se il destinatario non può subentrare come creatore perché ne possiede già 3
(§8), non riceve questo messaggio ma `GAME_CLOSED`.

## 9. Esempio di sessione con netcat

Con il server avviato, due terminali con `nc localhost 8080`. Le righe con
`>` sono scritte a mano, le altre arrivano dal server.

Terminale A:

```
WELCOME 1
> SET_USERNAME Anna
USERNAME_SET Anna
> CREATE_GAME Sfida_1
GAME_CREATED 1 Sfida_1
JOIN_NOTIFY 1 Marco
> JOIN_RESPONSE 1 1
```

Terminale B:

```
WELCOME 2
> SET_USERNAME Marco
USERNAME_SET Marco
> LIST_GAMES
GAME_LIST 1 1 Sfida_1 Anna
> JOIN_GAME 1
JOIN_RESULT 1 1
```
