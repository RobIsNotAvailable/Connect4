import { Injectable } from '@angular/core';
import { Observable, of, throwError } from 'rxjs';
import { delay, map, tap } from 'rxjs/operators';
import { Board, Game, GameSummary, PlayerColor } from '../models/game.model';
import { GameService } from './game.service';

const MOCK_LATENCY_MS = 300;

function emptyBoard(): Board {
  return Array.from({ length: 6 }, () => Array<PlayerColor | null>(7).fill(null));
}

function toSummary(game: Game): GameSummary {
  const { board: _board, currentTurnColor: _currentTurnColor, ...summary } = game;
  return summary;
}

@Injectable()
export class GameMockService extends GameService {
  private games: Game[] = [
    {
      id: 'g1',
      name: 'Partita di Marco',
      status: 'playing',
      players: [
        { id: 'u1', username: 'Marco', color: 'red' },
        { id: 'u2', username: 'Tu', color: 'blue' },
      ],
      yourColor: 'blue',
      isYourTurn: true,
      currentTurnColor: 'blue',
      board: [
        [null, null, null, null, null, null, null],
        [null, null, null, null, null, null, null],
        [null, null, null, 'red', null, null, null],
        [null, null, 'blue', 'red', null, null, null],
        [null, 'red', 'blue', 'blue', null, null, null],
        [null, 'blue', 'red', 'red', 'blue', null, null],
      ],
    },
    {
      id: 'g2',
      name: 'Partita di Giulia',
      status: 'playing',
      players: [
        { id: 'u3', username: 'Giulia', color: 'red' },
        { id: 'u2', username: 'Tu', color: 'blue' },
      ],
      yourColor: 'blue',
      isYourTurn: false,
      currentTurnColor: 'red',
      board: emptyBoard(),
    },
    {
      id: 'g3',
      name: 'In attesa di un avversario',
      status: 'waiting',
      players: [{ id: 'u2', username: 'Tu', color: 'red' }],
      yourColor: 'red',
      isYourTurn: false,
      currentTurnColor: 'red',
      board: emptyBoard(),
    },
  ];

  listGames(): Observable<GameSummary[]> {
    return of(this.games.map(toSummary)).pipe(delay(MOCK_LATENCY_MS));
  }

  getGame(id: string): Observable<Game> {
    const game = this.games.find((g) => g.id === id);
    if (!game) {
      return throwError(() => new Error(`Game ${id} not found`));
    }
    return of(game).pipe(delay(MOCK_LATENCY_MS));
  }

  createGame(name: string): Observable<GameSummary> {
    const newGame: Game = {
      id: `g${this.games.length + 1}`,
      name,
      status: 'waiting',
      players: [{ id: 'u2', username: 'Tu', color: 'red' }],
      yourColor: 'red',
      isYourTurn: false,
      currentTurnColor: 'red',
      board: emptyBoard(),
    };
    return of(newGame).pipe(
      delay(MOCK_LATENCY_MS),
      tap((game) => this.games.push(game)),
      map(toSummary)
    );
  }

  joinGame(gameId: string): Observable<GameSummary> {
    const game = this.games.find((g) => g.id === gameId);
    if (!game) {
      return throwError(() => new Error(`Game ${gameId} not found`));
    }
    game.status = 'playing';
    return of(game).pipe(delay(MOCK_LATENCY_MS), map(toSummary));
  }
}