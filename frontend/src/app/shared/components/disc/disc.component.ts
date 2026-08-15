import { Component, input } from '@angular/core';
import { PlayerColor } from '../../../core/models/game.model';

@Component({
  selector: 'app-disc',
  standalone: true,
  templateUrl: './disc.component.html',
  styleUrl: './disc.component.scss',
})
export class DiscComponent {
  color = input<PlayerColor | 'neutral'>('neutral');
  size = input<'sm' | 'md' | 'lg'>('md');
  active = input(false);
}