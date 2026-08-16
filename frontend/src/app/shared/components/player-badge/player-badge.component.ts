import { Component, input } from '@angular/core';
import { Player } from '../../../core/models/game.model';

@Component({
  selector: 'app-player-badge',
  standalone: true,
  templateUrl: './player-badge.component.html',
  styleUrl: './player-badge.component.scss',
})
export class PlayerBadgeComponent {
  player = input<Player | null>(null);
  align = input<'left' | 'right'>('left');
}