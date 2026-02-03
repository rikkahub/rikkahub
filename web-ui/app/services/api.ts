import axios, { AxiosError } from "axios";

interface ErrorResponse {
  error: string;
  code: number;
}

export class ApiError extends Error {
  code: number;

  constructor(message: string, code: number) {
    super(message);
    this.name = "ApiError";
    this.code = code;
  }
}

const api = axios.create({
  baseURL: "/api",
  timeout: 30000,
  headers: {
    "Content-Type": "application/json",
  },
});

api.interceptors.response.use(
  (response) => response.data,
  (error: AxiosError<ErrorResponse>) => {
    const data = error.response?.data;
    const code = data?.code ?? error.response?.status ?? 500;
    const message = data?.error ?? error.message;
    return Promise.reject(new ApiError(message, code));
  }
);

export default api;
