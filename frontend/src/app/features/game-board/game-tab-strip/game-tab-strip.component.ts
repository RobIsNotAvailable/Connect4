import { Component, input, output } from '@angular/core';
import { GameSummary } from '../../../core/models/game.model';
import { DiscComponent } from '../../../shared/components/disc/disc.component';

@Component({
  selector: 'app-game-tab-strip',
  standalone: true,
  imports: [DiscComponent],
  templateUrl: './game-tab-strip.component.html',
  styleUrl: './game-tab-strip.component.scss',
})
export class GameTabStripComponent {
  games = input.required<GameSummary[]>();
  activeGameId = input.required<string>();
  gameSelected = output<string>();
}