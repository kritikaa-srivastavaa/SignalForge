import { get, post } from './client';
import type { User } from '../types';

export interface LoginInput { email: string; password: string }
export interface RegisterInput extends LoginInput { displayName: string }
export const getCurrentUser = (signal?: AbortSignal) => get<User>('/auth/me', {}, signal);
export const login = (input: LoginInput) => post<User>('/auth/login', input);
export const register = (input: RegisterInput) => post<User>('/auth/register', input);
export const logout = () => post<void>('/auth/logout');
