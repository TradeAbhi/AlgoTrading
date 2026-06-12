import { useState, useEffect } from 'react';
import { deltaStatusApi } from '../api';

function DeltaScanner() {
  const [levels, setLevels] = useState({});
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [checkSymbol, setCheckSymbol] = useState('');

  const loadData = async () => {
    try {
      const [levelsData, statusData] = await Promise.all([
        deltaStatusApi.getLevels(),
        deltaStatusApi.getStatus()
      ]);
      setLevels(levelsData);
      setStatus(statusData);
    } catch (error) {
      setMessage('Error loading data: ' + error.message);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleCheckSymbol = async () => {
    if (!checkSymbol.trim()) {
      setMessage('Please enter a symbol');
      return;
    }
    
    setLoading(true);
    try {
      await deltaStatusApi.checkSymbol(checkSymbol.trim().toUpperCase());
      setMessage(`Check triggered for ${checkSymbol.toUpperCase()}`);
      setCheckSymbol('');
    } catch (error) {
      setMessage('Error checking symbol: ' + error.message);
    }
    setLoading(false);
  };

  const handleCheckAll = async () => {
    setLoading(true);
    try {
      await deltaStatusApi.checkAll();
      setMessage('Check triggered for all symbols');
    } catch (error) {
      setMessage('Error checking all: ' + error.message);
    }
    setLoading(false);
  };

  const handleRefreshLevels = async () => {
    setLoading(true);
    try {
      await deltaStatusApi.refreshLevels();
      setMessage('Levels refreshed');
      await loadData();
    } catch (error) {
      setMessage('Error refreshing levels: ' + error.message);
    }
    setLoading(false);
  };

  const handleTestTelegram = async () => {
    setLoading(true);
    try {
      await deltaStatusApi.testTelegram();
      setMessage('Test message sent to Telegram');
    } catch (error) {
      setMessage('Error sending test: ' + error.message);
    }
    setLoading(false);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>📊 Delta Alert Scanner</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      {/* Status Section */}
      {status && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>Service Status</h3>
          <div style={styles.statusGrid}>
            <div style={styles.statusItem}>
              <strong>Service:</strong> {status.service}
            </div>
            <div style={styles.statusItem}>
              <strong>Monitored Symbols:</strong> {status.monitoredSymbols?.length || 0}
            </div>
            <div style={styles.statusItem}>
              <strong>Levels Loaded:</strong> {status.levelsLoaded || 0}
            </div>
            <div style={styles.statusItem}>
              <strong>Server Time:</strong> {new Date(status.serverTime).toLocaleString()}
            </div>
          </div>
        </div>
      )}

      {/* Control Panel */}
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Control Panel</h3>
        <div style={styles.buttonGroup}>
          <button 
            onClick={handleRefreshLevels} 
            disabled={loading}
            style={styles.button}
          >
            🔄 Refresh Levels
          </button>
          <button 
            onClick={handleCheckAll} 
            disabled={loading}
            style={styles.button}
          >
            🔍 Check All Symbols
          </button>
          <button 
            onClick={handleTestTelegram} 
            disabled={loading}
            style={styles.button}
          >
            📱 Test Telegram
          </button>
          <button 
            onClick={loadData} 
            disabled={loading}
            style={styles.button}
          >
            📊 Reload Data
          </button>
        </div>
      </div>

      {/* Check Symbol Section */}
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Check Specific Symbol</h3>
        <div style={styles.inputGroup}>
          <input
            type="text"
            value={checkSymbol}
            onChange={(e) => setCheckSymbol(e.target.value)}
            placeholder="Enter symbol (e.g., BTCUSD)"
            style={styles.input}
          />
          <button 
            onClick={handleCheckSymbol} 
            disabled={loading}
            style={styles.button}
          >
            Check
          </button>
        </div>
      </div>

      {/* Levels Table */}
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Previous Day Levels ({Object.keys(levels).length})</h3>
        <div style={styles.tableContainer}>
          <table style={styles.table}>
            <thead>
              <tr>
                <th style={styles.th}>Symbol</th>
                <th style={styles.th}>Previous High</th>
                <th style={styles.th}>Previous Low</th>
                <th style={styles.th}>Previous Close</th>
                <th style={styles.th}>Level Type</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(levels).map(([symbol, level], index) => (
                <tr key={index} style={styles.tr}>
                  <td style={styles.td}><strong>{symbol}</strong></td>
                  <td style={styles.td}>{level.previousHigh ? level.previousHigh.toFixed(2) : 'N/A'}</td>
                  <td style={styles.td}>{level.previousLow ? level.previousLow.toFixed(2) : 'N/A'}</td>
                  <td style={styles.td}>{level.previousClose ? level.previousClose.toFixed(2) : 'N/A'}</td>
                  <td style={styles.td}>{level.levelType || 'N/A'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {Object.keys(levels).length === 0 && (
            <div style={styles.emptyState}>No levels loaded</div>
          )}
        </div>
      </div>
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
    marginBottom: '10px',
    fontSize: '16px'
  },
  statusGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
    gap: '10px'
  },
  statusItem: {
    padding: '10px',
    background: '#f8f9fa',
    borderRadius: '6px'
  },
  buttonGroup: {
    display: 'flex',
    gap: '10px',
    flexWrap: 'wrap'
  },
  button: {
    padding: '10px 20px',
    background: '#9c27b0',
    color: 'white',
    border: 'none',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '13px',
    fontWeight: '600',
    transition: 'background 0.2s'
  },
  inputGroup: {
    display: 'flex',
    gap: '10px',
    flexWrap: 'wrap'
  },
  input: {
    flex: 1,
    minWidth: '200px',
    padding: '10px',
    border: '1px solid #ddd',
    borderRadius: '6px',
    fontSize: '14px'
  },
  tableContainer: {
    overflowX: 'auto'
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse',
    fontSize: '13px'
  },
  th: {
    background: '#9c27b0',
    color: 'white',
    padding: '10px',
    textAlign: 'left',
    fontWeight: '600',
    whiteSpace: 'nowrap'
  },
  tr: {
    borderBottom: '1px solid #e0e0e0'
  },
  td: {
    padding: '10px',
    color: '#333'
  },
  emptyState: {
    textAlign: 'center',
    padding: '30px',
    color: '#999',
    fontSize: '14px'
  }
};

export default DeltaScanner;
