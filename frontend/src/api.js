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
