import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { GameService } from '../../core/services/game.service';
import { Game, GameSummary } from '../../core/models/game.model';
import { BoardGridComponent } from '../../shared/components/board-grid/board-grid.component';
import { PlayerBadgeComponent } from '../../shared/components/player-badge/player-badge.component';
import { GameTabStripComponent } from './game-tab-strip/game-tab-strip.component';

@Component({
  selector: 'app-game-board',
  standalone: true,
  imports: [BoardGridComponent, PlayerBadgeComponent, GameTabStripComponent, RouterLink],
  templateUrl: './game-board.component.html',
  styleUrl: './game-board.component.scss',
})
export class GameBoardComponent {
  private gameService = inject(GameService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  game = signal<Game | null>(null);
  openGames = signal<GameSummary[]>([]);

  // Il colore identifica sempre lo stesso lato del tabellone (blu a
  // sinistra, rosso a destra), indipendentemente da chi sei tu.
  bluePlayer = computed(() => this.game()?.players.find((p) => p.color === 'blue') ?? null);
  redPlayer = computed(() => this.game()?.players.find((p) => p.color === 'red') ?? null);

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
    const current = this.game();
    if (!current) return;

    this.gameService.dropDisc(current.id, column).subscribe({
      next: (game) => this.game.set(game),
      // Mossa non valida (partita non in corso, colonna piena): la ignoriamo
      // silenziosamente, non serve un errore bloccante lato UI per un dev-tool.
      error: (err) => console.warn(err.message),
    });
  }
}