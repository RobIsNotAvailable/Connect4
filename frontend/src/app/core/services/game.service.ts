import { Observable } from 'rxjs';
import { Game } from '../models/game.model';

export abstract class GameService {
  abstract listGames(): Observable<Game[]>;
  abstract createGame(name: string): Observable<Game>;
  abstract joinGame(gameId: string): Observable<Game>;
}