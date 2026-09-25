# Forza 4

Progetto di Laboratorio di Sistemi Operativi: Forza 4.

- **Server** in C: accetta i client su un socket TCP e li serve in parallelo,
  un thread per client.

- **Client** in Java Swing: lobby con le stanze, partite in schede, notifiche.

- I due parlano con un protocollo a righe di testo su TCP.

## Avvio con Docker Compose

Serve Docker con Docker Compose (su Windows e macOS: Docker Desktop). Il
compose avvia il server e **2 client**. I client aprono le loro finestre sullo
schermo del computer attraverso un server grafico X: come averlo dipende dal
sistema. `--build` serve la prima volta e dopo ogni modifica al codice.

### Windows 11

Serve Docker Desktop con WSL2. Il server grafico è WSLg, già incluso in
Windows 11. Tutti i comandi si lanciano dalla cartella del progetto.

**Dal terminale di WSL** (per esempio Ubuntu):

```sh
docker compose up --build
```

**Da PowerShell.** Docker Desktop vede il server grafico di WSLg in un altro
percorso, che va indicato con la variabile `X11_SOCKET`:

```powershell
$env:X11_SOCKET="/run/desktop/mnt/host/wslg/.X11-unix"
docker compose up --build
```

`$env:` vale solo per quel terminale. Per impostarla una volta per tutte:

```powershell
setx X11_SOCKET /run/desktop/mnt/host/wslg/.X11-unix
```

Poi si chiude e si riapre PowerShell (se si usa il terminale di VS Code, tutto
VS Code). Da lì basta `docker compose up`. La variabile vale solo per i
terminali di Windows, non per WSL.

### Linux

Serve un desktop grafico con X (Xorg, oppure XWayland sotto Wayland). Il
compose usa il `DISPLAY` della sessione. Prima di avviare bisogna permettere ai
container di aprire finestre:

```sh
xhost +local:
docker compose up --build
```

Quando hai finito, `xhost -local:` toglie il permesso.

### macOS

1. Installare [XQuartz](https://www.xquartz.org/).
2. Nelle sue impostazioni, alla voce Sicurezza, attivare "Allow connections
   from network clients".
3. Chiudere e riaprire XQuartz.
4. Da un terminale:

   ```sh
   xhost +localhost
   DISPLAY=host.docker.internal:0 docker compose up --build
   ```

### Scegliere quanti client

In qualsiasi terminale:

```sh
docker compose up    
```

Lo stesso si può fare con la variabile `CLIENTS`:

- **bash o zsh** (WSL, Linux, macOS): `CLIENTS=4 docker compose up`
- **PowerShell:** `$env:CLIENTS=4`, poi `docker compose up`

Per aggiungere client mentre è già tutto avviato, da un altro terminale che
abbia le stesse variabili del primo (`X11_SOCKET` in PowerShell, `DISPLAY` su
macOS):

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

