import { useState } from 'react';
import { backtestWinnerApi } from '../api';

function BacktestWinnerAnalysis() {
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [result, setResult] = useState(null);
  const [strategyName, setStrategyName] = useState('ORB');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');

  const handleAnalyze = async () => {
    setLoading(true);
    try {
      const response = await backtestWinnerApi.analyze(
        strategyName,
        fromDate || undefined,
        toDate || undefined
      );
      setResult(response);
      setMessage(`Winner analysis completed for ${response.strategy} (${response.from} to ${response.to})`);
    } catch (error) {
      setMessage('Error running analysis: ' + error.message);
    }
    setLoading(false);
  };

  const handleAnalyzeToday = async () => {
    setLoading(true);
    try {
      const response = await backtestWinnerApi.analyzeToday(strategyName);
      setResult(response);
      setMessage(`Today's winner analysis completed for ${response.strategy}`);
    } catch (error) {
      setMessage('Error running analysis: ' + error.message);
    }
    setLoading(false);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>🏆 Backtest Winner Analysis</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Run Analysis</h3>
        <div style={styles.formGroup}>
          <label style={styles.label}>Strategy:</label>
          <select
            value={strategyName}
            onChange={(e) => setStrategyName(e.target.value)}
            style={styles.input}
          >
            <option value="ORB">ORB (Opening Range Breakout)</option>
            <option value="DELTA">DELTA (Previous Day Level)</option>
            <option value="FIBO">FIBO (Fibonacci)</option>
            <option value="IPO">IPO</option>
          </select>
        </div>

        <div style={styles.formGroup}>
          <label style={styles.label}>From Date (optional, defaults to today):</label>
          <input
            type="date"
            value={fromDate}
            onChange={(e) => setFromDate(e.target.value)}
            style={styles.input}
          />
        </div>

        <div style={styles.formGroup}>
          <label style={styles.label}>To Date (optional, defaults to today):</label>
          <input
            type="date"
            value={toDate}
            onChange={(e) => setToDate(e.target.value)}
            style={styles.input}
          />
        </div>
        
        <div style={styles.buttonGroup}>
          <button 
            onClick={handleAnalyze} 
            disabled={loading} 
            style={styles.primaryButton}
          >
            {loading ? 'Analyzing...' : '▶ Analyze Date Range'}
          </button>
          
          <button 
            onClick={handleAnalyzeToday} 
            disabled={loading} 
            style={styles.secondaryButton}
          >
            {loading ? 'Analyzing...' : '📅 Analyze Today'}
          </button>
        </div>
      </div>

      {result && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>Analysis Result</h3>
          <div style={styles.resultGrid}>
            <div style={styles.resultItem}>
              <div style={styles.resultLabel}>Status</div>
              <div style={styles.resultValue}>{result.status}</div>
            </div>
            <div style={styles.resultItem}>
              <div style={styles.resultLabel}>Strategy</div>
              <div style={styles.resultValue}>{result.strategy}</div>
            </div>
            <div style={styles.resultItem}>
              <div style={styles.resultLabel}>From</div>
              <div style={styles.resultValue}>{result.from}</div>
            </div>
            <div style={styles.resultItem}>
              <div style={styles.resultLabel}>To</div>
              <div style={styles.resultValue}>{result.to}</div>
            </div>
          </div>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Analysis Details</h3>
        <div style={styles.infoList}>
          <div style={styles.infoItem}>
            <strong>🎯 Purpose:</strong> Analyze winning backtest trades to identify patterns
          </div>
          <div style={styles.infoItem}>
            <strong>🏆 Winner Criteria:</strong> TARGET_HIT, BREAKEVEN_EXIT, or EOD_EXIT with positive P&L
          </div>
          <div style={styles.infoItem}>
            <strong>📊 Indicators Analyzed:</strong> RSI(14), ATR Ratio, Volume Ratio, Gap %, 20-day MA
          </div>
          <div style={styles.infoItem}>
            <strong>🔍 Pattern Flags:</strong> Strong opening, breakout, high-volume breakout
          </div>
          <div style={styles.infoItem}>
            <strong>📈 Strategies Supported:</strong> ORB, DELTA, FIBO, IPO
          </div>
          <div style={styles.infoItem}>
            <strong>📱 Reporting:</strong> Automatic Telegram report with summary and top winners
          </div>
          <div style={styles.infoItem}>
            <strong>⏰ Schedule:</strong> ORB at 4:00 PM, DELTA at 4:05 PM (Mon-Fri)
          </div>
        </div>
      </div>
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
  formGroup: {
    marginBottom: '20px'
  },
  label: {
    display: 'block',
    marginBottom: '8px',
    fontWeight: '500',
    color: '#555'
  },
  input: {
    width: '100%',
    maxWidth: '300px',
    padding: '10px',
    border: '1px solid #ddd',
    borderRadius: '4px',
    fontSize: '14px'
  },
  buttonGroup: {
    display: 'flex',
    gap: '10px',
    flexWrap: 'wrap'
  },
  primaryButton: {
    padding: '12px 24px',
    background: '#667eea',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  secondaryButton: {
    padding: '12px 24px',
    background: '#48bb78',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  resultGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
    gap: '15px'
  },
  resultItem: {
    background: '#f8f9fa',
    padding: '15px',
    borderRadius: '4px',
    textAlign: 'center'
  },
  resultLabel: {
    fontSize: '12px',
    color: '#666',
    marginBottom: '5px'
  },
  resultValue: {
    fontSize: '18px',
    fontWeight: 'bold',
    color: '#333'
  },
  infoList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '10px'
  },
  infoItem: {
    padding: '10px',
    background: '#f8f9fa',
    borderRadius: '4px',
    fontSize: '14px',
    color: '#555'
  }
};

export default BacktestWinnerAnalysis;
