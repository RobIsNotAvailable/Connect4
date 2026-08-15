import { Observable } from 'rxjs';
import { Game, GameSummary } from '../models/game.model';

export abstract class GameService {
  abstract listGames(): Observable<GameSummary[]>;
  abstract getGame(id: string): Observable<Game>;
  abstract createGame(name: string): Observable<GameSummary>;
  abstract joinGame(gameId: string): Observable<GameSummary>;
}