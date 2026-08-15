import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { GameService } from './core/services/game.service';
import { GameMockService } from './core/services/game-mock.service';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    { provide: GameService, useClass: GameMockService },
    provideRouter(routes)
  ]
};
