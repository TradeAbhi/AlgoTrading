import { useState } from 'react';
import { stockAnalyzerApi } from '../api';

function StockAnalyzer() {
  const [symbol, setSymbol] = useState('');
  const [timeframe, setTimeframe] = useState('DAILY');
  const [consolidationStart, setConsolidationStart] = useState('');
  const [consolidationEnd, setConsolidationEnd] = useState('');
  const [candleFromDate, setCandleFromDate] = useState('');
  const [candleToDate, setCandleToDate] = useState('');
  const [candles, setCandles] = useState([]);
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [fetchingCandles, setFetchingCandles] = useState(false);
  const [message, setMessage] = useState('');

  const handleFetchCandles = async () => {
    if (!symbol.trim()) {
      setMessage('Please enter a symbol');
      return;
    }

    if (!candleFromDate || !candleToDate) {
      setMessage('Please enter candle data date range');
      return;
    }

    setFetchingCandles(true);
    try {
      const response = await stockAnalyzerApi.fetchCandles(
        symbol.toUpperCase(),
        timeframe,
        candleFromDate,
        candleToDate
      );

      if (response.error) {
        setMessage('Error fetching candles: ' + response.error);
        setCandles([]);
      } else {
        setCandles(response.candles);
        setMessage(`Fetched ${response.candles.length} candles from Upstox`);
      }
    } catch (error) {
      setMessage('Error fetching candles: ' + error.message);
      setCandles([]);
    }
    setFetchingCandles(false);
  };

  const handleAnalyze = async () => {
    if (!symbol.trim()) {
      setMessage('Please enter a symbol');
      return;
    }

    if (!consolidationStart || !consolidationEnd) {
      setMessage('Please enter consolidation start and end dates');
      return;
    }

    if (candles.length === 0) {
      setMessage('Please fetch candle data first');
      return;
    }

    setLoading(true);
    try {
      const request = {
        symbol: symbol.toUpperCase(),
        timeframe: timeframe,
        consolidationStart: consolidationStart,
        consolidationEnd: consolidationEnd,
        candles: candles
      };

      const response = await stockAnalyzerApi.analyzeConsolidationBreakout(request);
      setResult(response);
      setMessage('Analysis completed successfully');
    } catch (error) {
      setMessage('Error analyzing: ' + error.message);
      setResult(null);
    }
    setLoading(false);
  };

  const formatNumber = (num) => num ? num.toFixed(2) : 'N/A';

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>📊 Stock Analyzer</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      {/* Input Section */}
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Analysis Parameters</h3>
        
        <div style={styles.formGroup}>
          <label style={styles.label}>Symbol:</label>
          <input
            type="text"
            value={symbol}
            onChange={(e) => setSymbol(e.target.value)}
            placeholder="AAPL"
            style={styles.input}
          />
        </div>

        <div style={styles.formGroup}>
          <label style={styles.label}>Timeframe:</label>
          <select
            value={timeframe}
            onChange={(e) => setTimeframe(e.target.value)}
            style={styles.input}
          >
            <option value="FIFTEEN_MIN">15 Minutes</option>
            <option value="DAILY">Daily</option>
            <option value="WEEKLY">Weekly</option>
          </select>
        </div>

        <div style={styles.formGroup}>
          <label style={styles.label}>Consolidation Start:</label>
          <input
            type="datetime-local"
            value={consolidationStart}
            onChange={(e) => setConsolidationStart(e.target.value)}
            style={styles.input}
          />
        </div>

        <div style={styles.formGroup}>
          <label style={styles.label}>Consolidation End:</label>
          <input
            type="datetime-local"
            value={consolidationEnd}
            onChange={(e) => setConsolidationEnd(e.target.value)}
            style={styles.input}
          />
        </div>

        <div style={styles.formGroup}>
          <label style={styles.label}>Candle Data From Date:</label>
          <input
            type="date"
            value={candleFromDate}
            onChange={(e) => setCandleFromDate(e.target.value)}
            style={styles.input}
          />
        </div>

        <div style={styles.formGroup}>
          <label style={styles.label}>Candle Data To Date:</label>
          <input
            type="date"
            value={candleToDate}
            onChange={(e) => setCandleToDate(e.target.value)}
            style={styles.input}
          />
        </div>

        <div style={styles.buttonGroup}>
          <button
            onClick={handleFetchCandles}
            disabled={fetchingCandles}
            style={{...styles.button, background: '#4caf50'}}
          >
            {fetchingCandles ? 'Fetching...' : '📥 Fetch Candles from Upstox'}
          </button>
          <button
            onClick={handleAnalyze}
            disabled={loading || candles.length === 0}
            style={styles.button}
          >
            {loading ? 'Analyzing...' : '🔍 Analyze'}
          </button>
        </div>

        {candles.length > 0 && (
          <div style={styles.candleInfo}>
            <strong>Candles Loaded:</strong> {candles.length} candles from Upstox
          </div>
        )}
      </div>

      {/* Results Section */}
      {result && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>Analysis Results</h3>
          
          <div style={styles.resultGroup}>
            <h4 style={styles.resultTitle}>Range Information</h4>
            <p><strong>Range High:</strong> ${formatNumber(result.rangeHigh)}</p>
            <p><strong>Range Low:</strong> ${formatNumber(result.rangeLow)}</p>
          </div>

          {result.conclusion && (
            <div style={styles.resultGroup}>
              <h4 style={styles.resultTitle}>Conclusion</h4>
              <p><strong>Direction:</strong> 
                <span style={{
                  ...styles.badge,
                  ...(result.conclusion.direction === 'BULLISH' ? styles.bullish : 
                      result.conclusion.direction === 'BEARISH' ? styles.bearish : {})
                }}>
                  {result.conclusion.direction}
                </span>
              </p>
              <p><strong>Primary Driver:</strong> {result.conclusion.primaryDriver}</p>
              <p><strong>Confidence Score:</strong> {formatNumber(result.conclusion.confidenceScore)}</p>
              <p><strong>Narrative:</strong> {result.conclusion.narrative}</p>
            </div>
          )}

          {result.orderFlow && (
            <div style={styles.resultGroup}>
              <h4 style={styles.resultTitle}>Order Flow Analysis</h4>
              <pre style={styles.json}>{JSON.stringify(result.orderFlow, null, 2)}</pre>
            </div>
          )}

          {result.marketStructure && (
            <div style={styles.resultGroup}>
              <h4 style={styles.resultTitle}>Market Structure</h4>
              <pre style={styles.json}>{JSON.stringify(result.marketStructure, null, 2)}</pre>
            </div>
          )}

          {result.volumeConfirmation && (
            <div style={styles.resultGroup}>
              <h4 style={styles.resultTitle}>Volume Confirmation</h4>
              <pre style={styles.json}>{JSON.stringify(result.volumeConfirmation, null, 2)}</pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

const styles = {
  container: {
    padding: '20px',
    background: '#f8f9fa',
    borderRadius: '8px',
    marginBottom: '20px'
  },
  title: {
    color: '#333',
    marginBottom: '20px',
    fontSize: '22px'
  },
  message: {
    background: '#e3f2fd',
    border: '1px solid #2196f3',
    borderRadius: '8px',
    padding: '12px',
    marginBottom: '20px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    color: '#1976d2'
  },
  closeBtn: {
    background: 'none',
    border: 'none',
    fontSize: '20px',
    cursor: 'pointer',
    color: '#1976d2'
  },
  section: {
    marginBottom: '20px',
    padding: '15px',
    background: 'white',
    borderRadius: '8px',
    border: '1px solid #e0e0e0'
  },
  sectionTitle: {
    color: '#333',
    marginBottom: '15px',
    fontSize: '16px'
  },
  formGroup: {
    marginBottom: '15px'
  },
  label: {
    display: 'block',
    marginBottom: '5px',
    color: '#555',
    fontSize: '14px',
    fontWeight: '500'
  },
  input: {
    width: '100%',
    padding: '10px',
    border: '1px solid #ddd',
    borderRadius: '6px',
    fontSize: '14px'
  },
  textarea: {
    width: '100%',
    height: '150px',
    padding: '10px',
    border: '1px solid #ddd',
    borderRadius: '6px',
    fontFamily: 'monospace',
    fontSize: '13px',
    resize: 'vertical'
  },
  button: {
    padding: '12px 24px',
    background: '#667eea',
    color: 'white',
    border: 'none',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '600',
    transition: 'background 0.2s'
  },
  buttonGroup: {
    display: 'flex',
    gap: '10px',
    marginTop: '10px'
  },
  candleInfo: {
    marginTop: '15px',
    padding: '10px',
    background: '#e8f5e9',
    border: '1px solid #4caf50',
    borderRadius: '6px',
    color: '#2e7d32',
    fontSize: '14px'
  },
  resultGroup: {
    marginBottom: '20px',
    padding: '15px',
    background: '#f5f5f5',
    borderRadius: '6px'
  },
  resultTitle: {
    color: '#333',
    marginBottom: '10px',
    fontSize: '14px',
    fontWeight: '600'
  },
  badge: {
    display: 'inline-block',
    padding: '4px 12px',
    borderRadius: '4px',
    fontSize: '12px',
    fontWeight: '600',
    marginLeft: '8px'
  },
  bullish: {
    background: '#e8f5e9',
    color: '#2e7d32'
  },
  bearish: {
    background: '#ffebee',
    color: '#c62828'
  },
  json: {
    background: '#263238',
    color: '#eceff1',
    padding: '12px',
    borderRadius: '4px',
    fontSize: '12px',
    overflowX: 'auto'
  }
};

export default StockAnalyzer;
