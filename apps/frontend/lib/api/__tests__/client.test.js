import { beforeEach, describe, expect, it } from 'vitest';
import {
  LAST_TOKEN_VERIFICATION_KEY,
  USER_STORAGE_KEY,
} from '../../auth/authStorage';
import { createApiClient, getAuthHeaders } from '../client';

const readHeader = (headers, name) => headers.get?.(name) ?? headers[name];

describe('api client', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('builds auth headers from a session', () => {
    expect(
      getAuthHeaders({
        token: 'token-1',
        sessionId: 'session-1',
      })
    ).toEqual({
      'x-auth-token': 'token-1',
      'x-session-id': 'session-1',
    });
  });

  it('injects stored auth headers into requests', async () => {
    const client = createApiClient({
      baseURL: 'http://api.test',
      getSession: () => ({
        token: 'token-1',
        sessionId: 'session-1',
      }),
    });

    client.defaults.adapter = async (config) => ({
      config,
      data: { ok: true },
      headers: {},
      status: 200,
      statusText: 'OK',
    });

    const response = await client.get('/api/rooms');

    expect(readHeader(response.config.headers, 'x-auth-token')).toBe('token-1');
    expect(readHeader(response.config.headers, 'x-session-id')).toBe('session-1');
  });

  it('does not retry requests marked with skipRetry', async () => {
    const client = createApiClient({ baseURL: 'http://api.test', getSession: () => null });
    let attempts = 0;

    client.defaults.adapter = async (config) => {
      attempts++;
      const error = new Error('Gateway Timeout');
      error.config = config;
      error.response = { config, data: {}, headers: {}, status: 504, statusText: 'Gateway Timeout' };
      throw error;
    };

    await expect(client.post('/api/auth/login', {}, { skipRetry: true })).rejects.toMatchObject({
      status: 504,
    });
    expect(attempts).toBe(1);
  });

  it('respects skipAuth requests', async () => {
    const client = createApiClient({
      baseURL: 'http://api.test',
      getSession: () => ({
        token: 'token-1',
        sessionId: 'session-1',
      }),
    });

    client.defaults.adapter = async (config) => ({
      config,
      data: { ok: true },
      headers: {},
      status: 200,
      statusText: 'OK',
    });

    const response = await client.post('/api/auth/login', {}, { skipAuth: true });

    expect(readHeader(response.config.headers, 'x-auth-token')).toBeUndefined();
    expect(readHeader(response.config.headers, 'x-session-id')).toBeUndefined();
  });

  it('clears stored users on auth expiration', async () => {
    localStorage.setItem(
      USER_STORAGE_KEY,
      JSON.stringify({
        id: 'user-1',
        token: 'token-1',
      })
    );
    localStorage.setItem(LAST_TOKEN_VERIFICATION_KEY, '3000');

    const client = createApiClient({
      baseURL: 'http://api.test',
      getSession: () => ({
        token: 'token-1',
      }),
    });

    client.defaults.adapter = async (config) => {
      const error = new Error('Unauthorized');
      error.config = config;
      error.response = {
        config,
        data: {},
        headers: {},
        status: 401,
        statusText: 'Unauthorized',
      };
      throw error;
    };

    await expect(client.get('/api/profile')).rejects.toMatchObject({
      code: 'AUTH_EXPIRED',
      status: 401,
    });
    expect(localStorage.getItem(USER_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(LAST_TOKEN_VERIFICATION_KEY)).toBeNull();
  });

  it('can leave 401 responses to endpoint-specific handlers', async () => {
    const client = createApiClient({
      baseURL: 'http://api.test',
      getSession: () => null,
    });

    client.defaults.adapter = async (config) => {
      const error = new Error('Unauthorized');
      error.config = config;
      error.response = {
        config,
        data: { message: 'invalid credentials' },
        headers: {},
        status: 401,
        statusText: 'Unauthorized',
      };
      throw error;
    };

    await expect(
      client.post('/api/auth/login', {}, { skipAuth: true, handleAuthError: false })
    ).rejects.toMatchObject({
      response: {
        status: 401,
        data: { message: 'invalid credentials' },
      },
    });
  });
});
