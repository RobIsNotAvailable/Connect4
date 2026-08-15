import { Component, input, output } from '@angular/core';
import { Board } from '../../../core/models/game.model';
import { DiscComponent } from '../disc/disc.component';

@Component({
  selector: 'app-board-grid',
  standalone: true,
  imports: [DiscComponent],
  templateUrl: './board-grid.component.html',
  styleUrl: './board-grid.component.scss',
})
export class BoardGridComponent {
  board = input.required<Board>();
  columnClick = output<number>();

  readonly columnIndices = [0, 1, 2, 3, 4, 5, 6];
  readonly rowIndices = [0, 1, 2, 3, 4, 5];
}