import { useState } from 'react';
import { moverAnalysisApi } from '../api';

function MoverAnalysis() {
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [result, setResult] = useState(null);
  const [date, setDate] = useState('');

  const handleRun = async () => {
    const targetDate = date || new Date().toISOString().split('T')[0];
    setLoading(true);
    try {
      const response = await moverAnalysisApi.run(targetDate);
      setResult(response);
      setMessage(`Mover analysis completed for ${response.date}`);
    } catch (error) {
      setMessage('Error running analysis: ' + error.message);
    }
    setLoading(false);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>📊 Mover Analysis</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Run Analysis</h3>
        <div style={styles.formGroup}>
          <label style={styles.label}>Date (optional, defaults to today):</label>
          <input
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            style={styles.input}
          />
        </div>
        
        <button 
          onClick={handleRun} 
          disabled={loading} 
          style={styles.primaryButton}
        >
          {loading ? 'Running...' : '▶ Run Mover Analysis'}
        </button>
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
              <div style={styles.resultLabel}>Date</div>
              <div style={styles.resultValue}>{result.date}</div>
            </div>
          </div>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Analysis Details</h3>
        <div style={styles.infoList}>
          <div style={styles.infoItem}>
            <strong>📊 Analysis:</strong> Identifies top movers in the market
          </div>
          <div style={styles.infoItem}>
            <strong>📈 Metrics:</strong> Price change, volume, percentage change
          </div>
          <div style={styles.infoItem}>
            <strong>🎯 Universe:</strong> NSE F&O stocks
          </div>
          <div style={styles.infoItem}>
            <strong>📅 Date:</strong> Can analyze any historical date or today
          </div>
          <div style={styles.infoItem}>
            <strong>⚡ Use Case:</strong> Identify strong momentum stocks for trading
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

export default MoverAnalysis;
