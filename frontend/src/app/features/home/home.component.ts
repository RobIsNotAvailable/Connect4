import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { GameService } from '../../core/services/game.service';
import { GameSummary } from '../../core/models/game.model';
import { DiscComponent } from '../../shared/components/disc/disc.component';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [DiscComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent {
  private gameService = inject(GameService);
  private router = inject(Router);

  games = signal<GameSummary[]>([]);
  newGameName = signal('');
  showCreateForm = signal(false);

  constructor() {
    this.refreshGames();
  }

  refreshGames() {
    this.gameService.listGames().subscribe((games) => this.games.set(games));
  }

  toggleCreateForm() {
    this.showCreateForm.set(!this.showCreateForm());
  }

  confirmCreate() {
    const name = this.newGameName().trim();
    if (!name) return;
    this.gameService.createGame(name).subscribe((game) => {
      this.games.update((list) => [...list, game]);
      this.newGameName.set('');
      this.showCreateForm.set(false);
      this.router.navigate(['/game', game.id]);
    });
  }

  openGame(game: GameSummary) {
    this.router.navigate(['/game', game.id]);
  }
}