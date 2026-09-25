# Forza 4

Progetto di Laboratorio di Sistemi Operativi: Forza 4.

- **Server** in C: accetta i client su un socket TCP e li serve in parallelo,
  un thread per client.

- **Client** in Java Swing: lobby con le stanze, partite in schede, notifiche.

- I due parlano con un protocollo a righe di testo, descritto in
  [`docs/protocol.md`](docs/protocol.md).

## Avvio con Docker Compose

Serve Docker con Docker Compose. I client aprono le loro finestre sullo
schermo del computer, quindi serve anche un server grafico X (vedi
[Finestre dei client](#finestre-dei-client)).

Dalla cartella del progetto:

```sh
docker compose up --build
```

Costruisce le immagini e avvia il server e **2 client**, cioè due finestre.
`--build` serve la prima volta e dopo ogni modifica al codice.

### Scegliere quanti client

```sh
CLIENTS=4 docker compose up
docker compose up --scale clients=4
```

I due comandi sono equivalenti. Per aggiungere client mentre è già tutto
avviato, da un altro terminale:

```sh
docker compose up -d --scale clients=6
```

Compose avvia solo i client mancanti. Server e partite in corso restano come
sono.

### Fermare

- Chiudere una finestra ferma solo quel client.
- Ctrl+C nel terminale di `docker compose up` ferma tutto.
- `docker compose down` rimuove anche i container.

Log del server: `docker compose logs -f server`.

Il server è raggiungibile anche dall'host sulla porta **8080**, quindi un client
avviato fuori da Docker (vedi sotto) può giocare con quelli nei container.

### Finestre dei client

I container disegnano sul server X dell'host. Il compose passa loro `DISPLAY` e
monta `/tmp/.X11-unix`.

- **Windows 11 (WSL2 con WSLg):** funziona così com'è, lanciando i comandi dal
  terminale di WSL.
- **Linux (Xorg):** prima di avviare bisogna permettere ai container di aprire
  finestre:
  ```sh
  xhost +local:
  ```
  Quando hai finito, con `xhost -local:` il permesso si toglie.
- **macOS:** installare [XQuartz](https://www.xquartz.org/) e, nelle sue
  impostazioni (Sicurezza), attivare "Allow connections from network clients".
  Poi, riavviato XQuartz:
  ```sh
  xhost +localhost
  DISPLAY=host.docker.internal:0 docker compose up --build
  ```

## Avvio senza Docker

**Server.** Servono `gcc` e `make`:

```sh
make
./bin/server
```

Ascolta sulla porta 8080.

**Client.** Servono Java 21 e Maven:

```sh
cd frontend
mvn -q package dependency:copy-dependencies -DoutputDirectory=target/lib
java -cp "target/connect4-client-1.0-SNAPSHOT.jar:target/lib/*" com.lso.MainController
```

Si collega a `127.0.0.1:8080`. Per un altro server:
`java -Dserver.host=<indirizzo> -Dserver.port=<porta> -cp ...`. Ogni client è
una finestra: per giocare ne servono almeno due.

## Come si gioca

1. **All'avvio** si sceglie uno username (fino a 20 caratteri, unico tra i
   giocatori connessi).
2. **Nella lobby:**
   - *Open Rooms* elenca le stanze che aspettano un avversario;
   - *My Games* elenca le proprie stanze e partite.
3. **Create Room** apre una stanza, che resta in attesa di un avversario.
4. **Join Room** (o doppio clic sulla stanza) chiede al proprietario di
   entrare, e il proprietario accetta o rifiuta. Se è impegnato in una partita,
   la richiesta lo aspetta nella campanella.
5. **Per giocare** si clicca la colonna (un disco trasparente mostra dove
   cadrà), si usano i bottoni *Col*, oppure i tasti **1-7**.
6. **Home** torna alla lobby lasciando la partita in sospeso; **Abandon** la
   abbandona. Si possono avere fino a 5 stanze e partite insieme, una scheda per
   partita, ma si gioca in una alla volta.
7. **A fine partita**, *Rematch* ricomincia con la griglia vuota quando lo
   chiedono entrambi, mentre *Leave Room* esce dalla stanza.

## Test

**Server.** Test end-to-end: ognuno avvia la propria copia del server e ci
gioca con dei client simulati. Serve Python 3:

```sh
make test
```

Usano la porta 8080, quindi nessun altro server deve essere acceso, nemmeno
quello di Docker (`docker compose down`).

**Client.** I test fanno girare il vero client senza finestra e senza server:
fanno loro da server e controllano cosa il client mostra e cosa risponde.
Servono Java 21 e Maven:

```sh
frontend/test/run.sh             # tutti
frontend/test/run.sh FlowTest    # solo quelli indicati
```

Non scrivono niente nel progetto e usano la porta 18082, quindi possono girare
anche con il server acceso.

