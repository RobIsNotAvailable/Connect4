import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { delay, map, tap } from 'rxjs/operators';
import { Game } from '../models/game.model';
import { GameService } from './game.service';

const MOCK_LATENCY_MS = 300;

@Injectable()
export class GameMockService extends GameService {
  // In-memory data standing in for the backend until it's ready.
  private games: Game[] = [
    {
      id: 'g1',
      name: 'Partita di Marco',
      status: 'playing',
      players: [
        { id: 'u1', username: 'Marco', color: 'red' },
        { id: 'u2', username: 'Tu', color: 'blue' },
      ],
      isYourTurn: true,
    },
    {
      id: 'g2',
      name: 'Partita di Giulia',
      status: 'playing',
      players: [
        { id: 'u3', username: 'Giulia', color: 'red' },
        { id: 'u2', username: 'Tu', color: 'blue' },
      ],
      isYourTurn: false,
    },
    {
      id: 'g3',
      name: 'In attesa di un avversario',
      status: 'waiting',
      players: [{ id: 'u2', username: 'Tu', color: 'red' }],
      isYourTurn: false,
    },
  ];

  listGames(): Observable<Game[]> {
    return of(this.games).pipe(delay(MOCK_LATENCY_MS));
  }

  createGame(name: string): Observable<Game> {
    const newGame: Game = {
      id: `g${this.games.length + 1}`,
      name,
      status: 'waiting',
      players: [{ id: 'u2', username: 'Tu', color: 'red' }],
      isYourTurn: false,
    };
    return of(newGame).pipe(
      delay(MOCK_LATENCY_MS),
      tap((game) => this.games.push(game))
    );
  }

  joinGame(gameId: string): Observable<Game> {
    return of(this.games.find((g) => g.id === gameId)).pipe(
      delay(MOCK_LATENCY_MS),
      map((game) => {
        if (!game) throw new Error(`Game ${gameId} not found`);
        game.status = 'playing';
        return game;
      })
    );
  }
}