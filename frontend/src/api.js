import axios from 'axios';

// US Weekly Breakout API
const US_WEEKLY_BASE = '/us-weekly';

export const usWeeklyApi = {
  capture: async () => {
    const response = await axios.get(`${US_WEEKLY_BASE}/capture`);
    return response.data;
  },

  scan: async () => {
    const response = await axios.get(`${US_WEEKLY_BASE}/scan`);
    return response.data;
  },

  uploadTickers: async (tickers) => {
    const response = await axios.post(`${US_WEEKLY_BASE}/upload-tickers`, tickers);
    return response.data;
  },

  getState: async () => {
    const response = await axios.get(`${US_WEEKLY_BASE}/state`);
    return response.data;
  },

  getWatching: async () => {
    const response = await axios.get(`${US_WEEKLY_BASE}/watching`);
    return response.data;
  },

  getAlerted: async () => {
    const response = await axios.get(`${US_WEEKLY_BASE}/alerted`);
    return response.data;
  },

  get52WeekHighs: async () => {
    const response = await axios.get(`${US_WEEKLY_BASE}/52wk`);
    return response.data;
  },

  scanWeek: async () => {
    const response = await axios.get(`${US_WEEKLY_BASE}/scan-week`);
    return response.data;
  }
};

// NSE Weekly Breakout API
const WEEKLY_BASE = '/weekly';

export const weeklyApi = {
  capture: async () => {
    const response = await axios.get(`${WEEKLY_BASE}/capture`);
    return response.data;
  },

  scan: async () => {
    const response = await axios.get(`${WEEKLY_BASE}/scan`);
    return response.data;
  },

  getState: async () => {
    const response = await axios.get(`${WEEKLY_BASE}/state`);
    return response.data;
  },

  getWatching: async () => {
    const response = await axios.get(`${WEEKLY_BASE}/watching`);
    return response.data;
  },

  getAlerted: async () => {
    const response = await axios.get(`${WEEKLY_BASE}/alerted`);
    return response.data;
  }
};

// IPO API
const IPO_BASE = '/api/ipo';

export const ipoApi = {
  sync: async () => {
    const response = await axios.post(`${IPO_BASE}/sync`);
    return response.data;
  },

  uploadCsv: async (file) => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await axios.post(`${IPO_BASE}/upload-csv`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
    return response.data;
  },

  listingOpenAlert: async () => {
    const response = await axios.post(`${IPO_BASE}/listing-open-alert`);
    return response.data;
  },

  listingEodAlert: async () => {
    const response = await axios.post(`${IPO_BASE}/listing-eod-alert`);
    return response.data;
  },

  upcomingSummary: async () => {
    const response = await axios.post(`${IPO_BASE}/upcoming-summary`);
    return response.data;
  },

  strategyScan: async () => {
    const response = await axios.post(`${IPO_BASE}/strategy-scan`);
    return response.data;
  },

  getAll: async () => {
    const response = await axios.get(`${IPO_BASE}/all`);
    return response.data;
  },

  getUpcoming: async () => {
    const response = await axios.get(`${IPO_BASE}/upcoming`);
    return response.data;
  }
};

// NSE 52-Week High API
const NSE_BASE = '/api/nse';

export const nseApi = {
  sendWeekHigh: async () => {
    const response = await axios.post(`${NSE_BASE}/52-week-high`);
    return response.data;
  },

  sendWeekLow: async () => {
    const response = await axios.post(`${NSE_BASE}/52-week-low`);
    return response.data;
  },

  sendBoth: async () => {
    const response = await axios.post(`${NSE_BASE}/52-week-both`);
    return response.data;
  },

  scanWeeklyCloseBreakout: async () => {
    const response = await axios.post(`${NSE_BASE}/52-week-high/weekly-close-breakout`);
    return response.data;
  },

  upload52Week: async (file) => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await axios.post(`${NSE_BASE}/upload-52-week`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
    return response.data;
  }
};

// Delta API
const DELTA_BASE = '/delta';

export const deltaApi = {
  runBacktest: async (params) => {
    const response = await axios.get(`${DELTA_BASE}/backtest`, { params });
    return response.data;
  },

  runVolumeBacktest: async (params) => {
    const response = await axios.get(`${DELTA_BASE}/volume-backtest`, { params });
    return response.data;
  }
};

// Delta Status API
const API_BASE = '/api';

export const deltaStatusApi = {
  getLevels: async () => {
    const response = await axios.get(`${API_BASE}/levels`);
    return response.data;
  },

  checkSymbol: async (symbol) => {
    const response = await axios.post(`${API_BASE}/check/${symbol}`);
    return response.data;
  },

  checkAll: async () => {
    const response = await axios.post(`${API_BASE}/check/all`);
    return response.data;
  },

  refreshLevels: async () => {
    const response = await axios.post(`${API_BASE}/refresh-levels`);
    return response.data;
  },

  testTelegram: async () => {
    const response = await axios.post(`${API_BASE}/test-telegram`);
    return response.data;
  },

  getStatus: async () => {
    const response = await axios.get(`${API_BASE}/status`);
    return response.data;
  }
};

// Fibo Strategy Backtest API
const FIBO_BACKTEST_BASE = '/api/backtest';

export const fiboBacktestApi = {
  run: async (from, to, clearOld = false) => {
    const response = await axios.post(`${FIBO_BACKTEST_BASE}/run`, null, {
      params: { from, to, clearOld }
    });
    return response.data;
  },

  getSummary: async (from, to) => {
    const response = await axios.get(`${FIBO_BACKTEST_BASE}/summary`, {
      params: { from, to }
    });
    return response.data;
  },

  getTrades: async (params) => {
    const response = await axios.get(`${FIBO_BACKTEST_BASE}/trades`, { params });
    return response.data;
  },

  clear: async (from, to) => {
    const response = await axios.delete(`${FIBO_BACKTEST_BASE}/clear`, {
      params: { from, to }
    });
    return response.data;
  }
};

// Live Strategy API
const LIVE_STRATEGY_BASE = '/api/live-strategy';

export const liveStrategyApi = {
  scan: async () => {
    const response = await axios.post(`${LIVE_STRATEGY_BASE}/scan`);
    return response.data;
  }
};

// Market Sentiment API
const SENTIMENT_BASE = '/api/sentiment';

export const sentimentApi = {
  morning: async () => {
    const response = await axios.get(`${SENTIMENT_BASE}/morning`);
    return response.data;
  },

  midday: async () => {
    const response = await axios.get(`${SENTIMENT_BASE}/midday`);
    return response.data;
  },

  preclose: async () => {
    const response = await axios.get(`${SENTIMENT_BASE}/preclose`);
    return response.data;
  },

  eod: async () => {
    const response = await axios.get(`${SENTIMENT_BASE}/eod`);
    return response.data;
  },

  pcrAlert: async () => {
    const response = await axios.get(`${SENTIMENT_BASE}/pcr-alert`);
    return response.data;
  },

  vixAlert: async () => {
    const response = await axios.get(`${SENTIMENT_BASE}/vix-alert`);
    return response.data;
  },

  breadthAlert: async () => {
    const response = await axios.get(`${SENTIMENT_BASE}/breadth-alert`);
    return response.data;
  }
};

// Watchlist API
const WATCHLIST_BASE = '/api/watchlist';

export const watchlistApi = {
  getFull: async () => {
    const response = await axios.get(`${WATCHLIST_BASE}`);
    return response.data;
  },

  alert: async () => {
    const response = await axios.post(`${WATCHLIST_BASE}/alert`);
    return response.data;
  },

  getHighOi: async () => {
    const response = await axios.get(`${WATCHLIST_BASE}/high-oi`);
    return response.data;
  },

  getTopGainers: async () => {
    const response = await axios.get(`${WATCHLIST_BASE}/top-gainers`);
    return response.data;
  },

  getTopLosers: async () => {
    const response = await axios.get(`${WATCHLIST_BASE}/top-losers`);
    return response.data;
  },

  getActiveByValue: async () => {
    const response = await axios.get(`${WATCHLIST_BASE}/active-by-value`);
    return response.data;
  },

  getVolumeShockers: async () => {
    const response = await axios.get(`${WATCHLIST_BASE}/volume-shockers`);
    return response.data;
  },

  getOnlyBuyers: async () => {
    const response = await axios.get(`${WATCHLIST_BASE}/only-buyers`);
    return response.data;
  },

  getOnlySellers: async () => {
    const response = await axios.get(`${WATCHLIST_BASE}/only-sellers`);
    return response.data;
  }
};

// Index Strength API
const INDEX_STRENGTH_BASE = '/api/index-strength';

export const indexStrengthApi = {
  alert: async () => {
    const response = await axios.post(`${INDEX_STRENGTH_BASE}/alert`);
    return response.data;
  }
};

// Mover Analysis API
const MOVER_ANALYSIS_BASE = '/api/mover-analysis';

export const moverAnalysisApi = {
  run: async (date) => {
    const response = await axios.post(`${MOVER_ANALYSIS_BASE}/run`, null, {
      params: { date }
    });
    return response.data;
  }
};

// Stock Analyzer API
const STOCK_ANALYZER_BASE = '/api/analysis';

export const stockAnalyzerApi = {
  analyzeConsolidationBreakout: async (request) => {
    const response = await axios.post(`${STOCK_ANALYZER_BASE}/consolidation-breakout`, request);
    return response.data;
  },

  fetchCandles: async (symbol, timeframe, fromDate, toDate) => {
    const response = await axios.get(`${STOCK_ANALYZER_BASE}/fetch-candles`, {
      params: { symbol, timeframe, fromDate, toDate }
    });
    return response.data;
  }
};

// Portfolio API
const PORTFOLIO_BASE = '/api/portfolio';

export const portfolioApi = {
  getHoldings: async () => {
    const response = await axios.get(`${PORTFOLIO_BASE}/holdings`);
    return response.data;
  },

  getHoldingsByBroker: async (broker) => {
    const response = await axios.get(`${PORTFOLIO_BASE}/holdings/${broker}`);
    return response.data;
  },

  triggerVolumeScan: async () => {
    const response = await axios.post(`${PORTFOLIO_BASE}/volume-scan`);
    return response.data;
  }
};
