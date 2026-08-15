import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { GameService } from '../../core/services/game.service';
import { Game, GameSummary } from '../../core/models/game.model';
import { BoardGridComponent } from '../../shared/components/board-grid/board-grid.component';
import { GameTabStripComponent } from './game-tab-strip/game-tab-strip.component';

@Component({
  selector: 'app-game-board',
  standalone: true,
  imports: [BoardGridComponent, GameTabStripComponent, RouterLink],
  templateUrl: './game-board.component.html',
  styleUrl: './game-board.component.scss',
})
export class GameBoardComponent {
  private gameService = inject(GameService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  game = signal<Game | null>(null);
  openGames = signal<GameSummary[]>([]);

  constructor() {
    // Angular riusa questo componente quando si passa da /game/g1 a /game/g2,
    // quindi ascoltiamo i cambi di parametro invece di leggerlo una sola volta.
    this.route.paramMap.subscribe((params) => {
      const id = params.get('id');
      if (id) this.loadGame(id);
    });
    this.gameService.listGames().subscribe((games) => this.openGames.set(games));
  }

  loadGame(id: string) {
    this.gameService.getGame(id).subscribe((game) => this.game.set(game));
  }

  onGameSelected(id: string) {
    this.router.navigate(['/game', id]);
  }

  onColumnClick(column: number) {
    // La logica di inserimento del disco arriva quando il protocollo col backend è pronto.
    console.log('drop in column', column);
  }
}