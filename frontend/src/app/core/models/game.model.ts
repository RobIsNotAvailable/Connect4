// src/app/core/models/game.model.ts

export type PlayerColor = 'red' | 'blue';
export type GameStatus = 'waiting' | 'playing' | 'finished';
export type Cell = PlayerColor | null;
export type Board = Cell[][]; 
export interface Player {
  id: string;
  username: string;
  color: PlayerColor;
}

export interface GameSummary {
  id: string;
  name: string;
  status: GameStatus;
  players: Player[];
  yourColor: PlayerColor;
  isYourTurn: boolean;
}

export interface Game extends GameSummary {
  board: Board;
  currentTurnColor: PlayerColor;
}