import { Component, input, output, signal } from '@angular/core';
import { Board, PlayerColor } from '../../../core/models/game.model';
import { DiscComponent } from '../disc/disc.component';

// Duplicata volutamente rispetto a GameMockService: qui è solo un aiuto
// di rendering (dove mostrare l'anteprima), non una regola di gioco.
function findLowestEmptyRow(board: Board, column: number): number | null {
  for (let row = board.length - 1; row >= 0; row--) {
    if (board[row][column] === null) return row;
  }
  return null;
}

@Component({
  selector: 'app-board-grid',
  standalone: true,
  imports: [DiscComponent],
  templateUrl: './board-grid.component.html',
  styleUrl: './board-grid.component.scss',
})
export class BoardGridComponent {
  board = input.required<Board>();
  nextColor = input<PlayerColor>('red');
  columnClick = output<number>();

  readonly columnIndices = [0, 1, 2, 3, 4, 5, 6];
  readonly rowIndices = [0, 1, 2, 3, 4, 5];

  hoveredColumn = signal<number | null>(null);

  // Chiamata direttamente dal template (non un computed()): il service
  // muta l'array board in place, quindi un computed() rischia di restare
  // con un valore cacheato quando il mouse resta sulla stessa colonna.
  previewRowFor(column: number): number | null {
    return findLowestEmptyRow(this.board(), column);
  }
}