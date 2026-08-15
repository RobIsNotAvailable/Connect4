export type PlayerColor = 'red' | 'blue';

export type GameStatus = 'waiting' | 'playing' | 'finished';

export interface Player {
  id: string;
  username: string;
  color: PlayerColor;
}

export interface Game {
  id: string;
  name: string;
  status: GameStatus;
  players: Player[];
  isYourTurn: boolean;
}