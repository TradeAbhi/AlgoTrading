import { useState } from 'react';
import { fiboBacktestApi } from '../api';

function FiboBacktest() {
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [summary, setSummary] = useState(null);
  const [trades, setTrades] = useState(null);
  
  const [params, setParams] = useState({
    from: '',
    to: '',
    clearOld: false
  });

  const handleRun = async () => {
    if (!params.from || !params.to) {
      setMessage('Please select both from and to dates');
      return;
    }

    setLoading(true);
    try {
      const result = await fiboBacktestApi.run(params.from, params.to, params.clearOld);
      setSummary(result);
      setMessage('Backtest started. This may take several minutes...');
    } catch (error) {
      setMessage('Error running backtest: ' + error.message);
    }
    setLoading(false);
  };

  const handleGetSummary = async () => {
    if (!params.from || !params.to) {
      setMessage('Please select both from and to dates');
      return;
    }

    setLoading(true);
    try {
      const result = await fiboBacktestApi.getSummary(params.from, params.to);
      setSummary(result);
      setMessage('Summary loaded');
    } catch (error) {
      setMessage('Error loading summary: ' + error.message);
    }
    setLoading(false);
  };

  const handleGetTrades = async () => {
    setLoading(true);
    try {
      const result = await fiboBacktestApi.getTrades({
        from: params.from,
        to: params.to
      });
      setTrades(result);
      setMessage(`Loaded ${result.length} trades`);
    } catch (error) {
      setMessage('Error loading trades: ' + error.message);
    }
    setLoading(false);
  };

  const handleClear = async () => {
    if (!params.from || !params.to) {
      setMessage('Please select both from and to dates');
      return;
    }

    setLoading(true);
    try {
      const result = await fiboBacktestApi.clear(params.from, params.to);
      setMessage(`Cleared ${result.deleted} trades`);
      setSummary(null);
      setTrades(null);
    } catch (error) {
      setMessage('Error clearing data: ' + error.message);
    }
    setLoading(false);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>📊 Fibo Strategy Backtest</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Backtest Parameters</h3>
        <div style={styles.formGrid}>
          <div style={styles.formGroup}>
            <label style={styles.label}>From Date:</label>
            <input
              type="date"
              value={params.from}
              onChange={(e) => setParams({...params, from: e.target.value})}
              style={styles.input}
            />
          </div>
          <div style={styles.formGroup}>
            <label style={styles.label}>To Date:</label>
            <input
              type="date"
              value={params.to}
              onChange={(e) => setParams({...params, to: e.target.value})}
              style={styles.input}
            />
          </div>
          <div style={styles.formGroup}>
            <label style={styles.checkbox}>
              <input
                type="checkbox"
                checked={params.clearOld}
                onChange={(e) => setParams({...params, clearOld: e.target.checked})}
              />
              Clear existing data
            </label>
          </div>
        </div>

        <div style={styles.buttonGroup}>
          <button onClick={handleRun} disabled={loading} style={styles.primaryButton}>
            {loading ? 'Running...' : '▶ Run Backtest'}
          </button>
          <button onClick={handleGetSummary} disabled={loading} style={styles.secondaryButton}>
            📊 Get Summary
          </button>
          <button onClick={handleGetTrades} disabled={loading} style={styles.secondaryButton}>
            📋 Get Trades
          </button>
          <button onClick={handleClear} disabled={loading} style={styles.dangerButton}>
            🗑 Clear Data
          </button>
        </div>
      </div>

      {summary && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>Backtest Summary</h3>
          <div style={styles.summaryGrid}>
            <div style={styles.summaryItem}>
              <div style={styles.summaryLabel}>Total Signals</div>
              <div style={styles.summaryValue}>{summary.totalSignals}</div>
            </div>
            <div style={styles.summaryItem}>
              <div style={styles.summaryLabel}>Win Rate</div>
              <div style={styles.summaryValue}>{summary.winRate?.toFixed(2)}%</div>
            </div>
            <div style={styles.summaryItem}>
              <div style={styles.summaryLabel}>Total P&L (₹)</div>
              <div style={styles.summaryValue}>{summary.totalPnlRupees?.toFixed(2)}</div>
            </div>
            <div style={styles.summaryItem}>
              <div style={styles.summaryLabel}>Expectancy</div>
              <div style={styles.summaryValue}>{summary.expectancy?.toFixed(2)}</div>
            </div>
            <div style={styles.summaryItem}>
              <div style={styles.summaryLabel}>Avg Win (pts)</div>
              <div style={styles.summaryValue}>{summary.avgWinPoints?.toFixed(2)}</div>
            </div>
            <div style={styles.summaryItem}>
              <div style={styles.summaryLabel}>Avg Loss (pts)</div>
              <div style={styles.summaryValue}>{summary.avgLossPoints?.toFixed(2)}</div>
            </div>
            <div style={styles.summaryItem}>
              <div style={styles.summaryLabel}>Avg RR</div>
              <div style={styles.summaryValue}>{summary.avgRR?.toFixed(2)}</div>
            </div>
            <div style={styles.summaryItem}>
              <div style={styles.summaryLabel}>Total Wins</div>
              <div style={styles.summaryValue}>{summary.totalWins}</div>
            </div>
            <div style={styles.summaryItem}>
              <div style={styles.summaryLabel}>Total Losses</div>
              <div style={styles.summaryValue}>{summary.totalLosses}</div>
            </div>
            <div style={styles.summaryItem}>
              <div style={styles.summaryLabel}>Symbols Scanned</div>
              <div style={styles.summaryValue}>{summary.totalSymbolsScanned}</div>
            </div>
          </div>
        </div>
      )}

      {trades && trades.length > 0 && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>Trades ({trades.length})</h3>
          <div style={styles.tableContainer}>
            <table style={styles.table}>
              <thead>
                <tr>
                  <th style={styles.th}>Symbol</th>
                  <th style={styles.th}>Date</th>
                  <th style={styles.th}>Direction</th>
                  <th style={styles.th}>Entry</th>
                  <th style={styles.th}>Exit</th>
                  <th style={styles.th}>P&L (₹)</th>
                  <th style={styles.th}>Outcome</th>
                </tr>
              </thead>
              <tbody>
                {trades.slice(0, 50).map((trade, index) => (
                  <tr key={index}>
                    <td style={styles.td}>{trade.symbol}</td>
                    <td style={styles.td}>{trade.tradeDate}</td>
                    <td style={styles.td}>{trade.direction}</td>
                    <td style={styles.td}>{trade.entryPrice?.toFixed(2)}</td>
                    <td style={styles.td}>{trade.exitPrice?.toFixed(2)}</td>
                    <td style={styles.td}>{trade.pnlRupees?.toFixed(2)}</td>
                    <td style={styles.td}>{trade.outcome}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {trades.length > 50 && (
              <div style={styles.tableFooter}>
                Showing first 50 of {trades.length} trades
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

const styles = {
  container: {
    padding: '20px',
    background: '#f8f9fa',
    borderRadius: '8px'
  },
  title: {
    color: '#333',
    marginBottom: '20px'
  },
  message: {
    padding: '12px',
    background: '#d4edda',
    border: '1px solid #c3e6cb',
    borderRadius: '4px',
    marginBottom: '20px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  closeBtn: {
    background: 'none',
    border: 'none',
    fontSize: '18px',
    cursor: 'pointer'
  },
  section: {
    background: 'white',
    padding: '20px',
    borderRadius: '8px',
    marginBottom: '20px'
  },
  sectionTitle: {
    color: '#666',
    marginBottom: '15px',
    fontSize: '18px'
  },
  formGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
    gap: '15px',
    marginBottom: '20px'
  },
  formGroup: {
    display: 'flex',
    flexDirection: 'column'
  },
  label: {
    marginBottom: '5px',
    fontWeight: '500',
    color: '#555'
  },
  input: {
    padding: '8px',
    border: '1px solid #ddd',
    borderRadius: '4px',
    fontSize: '14px'
  },
  checkbox: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    cursor: 'pointer'
  },
  buttonGroup: {
    display: 'flex',
    gap: '10px',
    flexWrap: 'wrap'
  },
  primaryButton: {
    padding: '10px 20px',
    background: '#667eea',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  secondaryButton: {
    padding: '10px 20px',
    background: '#6c757d',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  dangerButton: {
    padding: '10px 20px',
    background: '#dc3545',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  summaryGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
    gap: '15px'
  },
  summaryItem: {
    background: '#f8f9fa',
    padding: '15px',
    borderRadius: '4px',
    textAlign: 'center'
  },
  summaryLabel: {
    fontSize: '12px',
    color: '#666',
    marginBottom: '5px'
  },
  summaryValue: {
    fontSize: '20px',
    fontWeight: 'bold',
    color: '#333'
  },
  tableContainer: {
    overflowX: 'auto'
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse'
  },
  th: {
    padding: '10px',
    textAlign: 'left',
    background: '#f8f9fa',
    borderBottom: '2px solid #dee2e6',
    fontSize: '12px',
    fontWeight: '600'
  },
  td: {
    padding: '10px',
    borderBottom: '1px solid #dee2e6',
    fontSize: '12px'
  },
  tableFooter: {
    padding: '10px',
    textAlign: 'center',
    color: '#666',
    fontSize: '12px'
  }
};

export default FiboBacktest;
