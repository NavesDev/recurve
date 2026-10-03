import { setupServer } from 'msw/node';

/** The API stand-in for component tests. Each test declares the handlers it needs. */
export const server = setupServer();
