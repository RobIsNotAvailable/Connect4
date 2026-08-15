import { Routes } from '@angular/router';
import { HomeComponent } from './features/home/home.component';
import { GameBoardComponent } from './features/game-board/game-board.component';

export const routes: Routes = [
  { path: '', component: HomeComponent },
  { path: 'game/:id', component: GameBoardComponent },
];